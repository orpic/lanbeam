package dev.lanbeam

import android.content.Context
import android.os.Build
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The phone's own TLS identity: an EC P-256 key generated on the device and a self-signed
 * certificate for its current Wi-Fi IP address. No domain, no internet, no outside CA.
 * The key is kept in app-private storage; the certificate is re-issued only when the IP changes.
 */
object TlsIdentity {
    private const val ALIAS = "lanbeam"
    private val PASS = "lanbeam".toCharArray() // file lives in app-private storage

    /** SHA-256 of the current certificate, as browsers show it ("1A 2B 3C ..."). Each phone has its own. */
    @Volatile var fingerprint: String = ""
        private set

    fun sslContext(ctx: Context, ip: Inet4Address): SSLContext {
        val file = File(ctx.filesDir, "identity.p12")
        val ks = KeyStore.getInstance("PKCS12")
        var key: PrivateKey? = null
        var cert: X509Certificate? = null
        if (file.exists()) {
            runCatching {
                file.inputStream().use { ks.load(it, PASS) }
                key = ks.getKey(ALIAS, PASS) as? PrivateKey
                cert = ks.getCertificate(ALIAS) as? X509Certificate
            }
        }
        if (key == null || cert == null) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = kpg.generateKeyPair()
            key = kp.private
            cert = issue(kp.public, kp.private, ip)
        } else if (!hasIp(cert!!, ip) || cert!!.subjectX500Principal.name != "CN=LanBeam") {
            cert = issue(cert!!.publicKey, key!!, ip)
        }
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry(ALIAS, key, PASS, arrayOf(cert))
        file.outputStream().use { store.store(it, PASS) }
        fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(cert!!.encoded)
            .joinToString(" ") { "%02X".format(it) }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, PASS)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    private fun hasIp(cert: X509Certificate, ip: Inet4Address): Boolean =
        runCatching { cert.subjectAlternativeNames?.any { it[0] == 7 && it[1] == ip.hostAddress } == true }.getOrDefault(false)
            && runCatching { cert.checkValidity(); true }.getOrDefault(false)

    private fun issue(pub: PublicKey, priv: PrivateKey, ip: Inet4Address): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=LanBeam") // no device model: anyone connecting can read the certificate
        val b = JcaX509v3CertificateBuilder(
            name, BigInteger(63, SecureRandom()), Date(now - 86_400_000L), Date(now + 397L * 86_400_000L), name, pub,
        )
        b.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.iPAddress, ip.hostAddress)))
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        b.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(priv)
        return JcaX509CertificateConverter().getCertificate(b.build(signer))
    }
}
