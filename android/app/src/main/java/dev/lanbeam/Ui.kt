package dev.lanbeam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Prism palette (dark). One beam of colour; everything else stays quiet. */
private object Lb {
    val ink = Color(0xFF0A0B10)
    val surface = Color(0xFF14161F)
    val surface2 = Color(0xFF1C1F2B)
    val hero = Color(0xFF11131B)
    val line = Color(0xFF262A38)
    val text = Color(0xFFF5F7FF)
    val text2 = Color(0xFFB4B9CB)
    val muted = Color(0xFF8A90A6)
    val beam = Color(0xFF5B8CFF)
    val violet = Color(0xFFA66BFF)
    val pink = Color(0xFFFF6B9A)
    val amber = Color(0xFFFFB547)
    val ok = Color(0xFF3DDC97)
    val link = Color(0xFF8FB0FF)
    val spectrum = Brush.horizontalGradient(listOf(beam, violet, pink))
}

private val Mono = FontFamily.Monospace
private val tnum = "tnum"

@Composable
fun LanBeamApp() {
    val ctx = LocalContext.current
    val st by Hub.state.collectAsStateWithLifecycle()

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        for (uri in uris) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = "file"; var size = -1L
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = if (c.isNull(1)) -1 else c.getLong(1) }
            }
            if (size < 0) continue
            Hub.offers += Offer(Hub.randomId(9), uri, name, size, ctx.contentResolver.getType(uri) ?: "application/octet-stream")
        }
        Hub.publish()
    }
    val pickFiles = { picker.launch(arrayOf("*/*")) }

    Box(Modifier.fillMaxSize().background(Lb.ink)) {
        if (!st.running) StartScreen(st) else SharingScreen(st, pickFiles)
    }
}

// ------------------------------------------------------------------ start

@Composable
private fun StartScreen(st: UiState) {
    val ctx = LocalContext.current
    val here = remember { LinkInfo.here(ctx) }
    // Android 13+ has "Nearby devices" for making a hotspot; Android 10-12 need Location (and Location
    // switched on) instead. LanBeam never reads the location itself.
    val modern = android.os.Build.VERSION.SDK_INT >= 33
    val hotspotPerm = if (modern) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    fun locationOn(): Boolean = modern || ctx.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
    fun startDirect() {
        if (!locationOn()) {
            Hub.state.value = Hub.state.value.copy(error = "Turn on Location to use Direct. Android 12 and older need it to make a hotspot; LanBeam doesn't use your location.")
        } else ServerService.start(ctx)
    }
    val nearbyPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startDirect()
        else Hub.state.value = Hub.state.value.copy(
            error = if (modern) "Direct needs the Nearby devices permission to make its Wi-Fi."
            else "Direct needs the Location permission to make its Wi-Fi on this Android version. LanBeam doesn't use your location.",
        )
    }
    fun start() {
        if (st.mode != "direct") { ServerService.start(ctx); return }
        if (ctx.checkSelfPermission(hotspotPerm) != android.content.pm.PackageManager.PERMISSION_GRANTED) nearbyPerm.launch(hotspotPerm)
        else startDirect()
    }
    val wifiName = here?.name ?: "your Wi-Fi"

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogoMark(28); Text("LanBeam", color = Lb.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text("Share with a\nnearby phone", color = Lb.text, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lb.surface).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Glyph("wifi", Lb.link, 22)
            Column(Modifier.weight(1f)) {
                Text(if (here == null) "Not on Wi-Fi" else (here.name ?: "Connected to Wi-Fi"), color = Lb.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val bits = listOfNotNull(
                    if (here == null) "Direct still works" else "This phone's Wi-Fi",
                    here?.band, here?.let { if (it.open) "open, not secured" else "secured" },
                )
                Text(bits.joinToString(" · "), color = Lb.muted, fontSize = 13.sp)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How should the phones connect?", color = Lb.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            ModeCard(
                title = "Direct", badge = "Fastest", glyph = "direct", selected = st.mode == "direct",
                body = "This phone makes its own Wi-Fi for the other phone. Up to 80 MB/s. Internet pauses on both phones while sharing.",
            ) { Hub.state.value = Hub.state.value.copy(mode = "direct") }
            ModeCard(
                title = "Same Wi-Fi", badge = null, glyph = "wifi", selected = st.mode == "wifi",
                body = "Both phones on $wifiName. Internet keeps working. Slower, because the router sits in the middle.",
            ) { Hub.state.value = Hub.state.value.copy(mode = "wifi") }
        }

        when {
            here?.open == true -> Notice("This Wi-Fi is open, so others on it could see this phone. Use Direct.")
            here?.slow == true && st.mode == "wifi" -> Notice("This Wi-Fi is on 2.4 GHz, the slow band. Direct is up to 5× faster.")
            st.mode == "wifi" && here == null -> Notice("This phone isn't on Wi-Fi. Connect both phones to the same Wi-Fi, or use Direct.")
        }
        st.error?.let { Notice(it) }

        Spacer(Modifier.weight(1f))
        PrimaryButton("Start sharing", Modifier.fillMaxWidth().height(56.dp)) { start() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Glyph("lock", Lb.muted, 13); Spacer(Modifier.width(6.dp))
            Text("Encrypted · files never leave this room", color = Lb.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ModeCard(title: String, badge: String?, glyph: String, selected: Boolean, body: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Lb.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Lb.beam else Lb.line, RoundedCornerShape(24.dp))
            .clickable(role = Role.RadioButton) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onClick() }
            .semantics { contentDescription = "$title${if (selected) ", selected" else ""}" }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(glyph, Lb.text, 24)
            Text(title, color = Lb.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (badge != null) Text(
                badge, color = Lb.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Brush.horizontalGradient(listOf(Lb.beam, Lb.violet))).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(body, color = Lb.text2, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

// ------------------------------------------------------------------ sharing

@Composable
private fun SharingScreen(st: UiState, pickFiles: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val direct = st.wifiQr != null
    val paired = st.peers.any { it.stage == PeerStage.PAIRED }
    val joined = st.peers.isNotEmpty()
    var showQr by rememberSaveable { mutableStateOf(true) }
    var step by rememberSaveable(st.wifiQr) { mutableStateOf(if (direct) 1 else 2) }
    var cantScan by rememberSaveable { mutableStateOf(false) }

    // The other phone joined our Wi-Fi: go to the page QR by itself, with a little tap.
    LaunchedEffect(joined) { if (joined && step == 1) { step = 2; haptic.performHapticFeedback(HapticFeedbackType.Confirm) } }
    // Once paired, the live view takes over; the QR stays one tap away.
    LaunchedEffect(paired) { if (paired) { showQr = false; haptic.performHapticFeedback(HapticFeedbackType.Confirm) } else showQr = true }

    val qrVisible = showQr || !paired
    var wake by remember { mutableStateOf(0) }
    QrMode(qrVisible, wakeKey = wake * 10 + step)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(36); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MonoLabel(if (direct) "DIRECT · 5 GHz · ENCRYPTED" else "SAME WI-FI · ENCRYPTED")
                    Text("Sharing", color = Lb.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                QuietButton("Stop") { ServerService.stop(ctx) }
            }

            if (qrVisible) {
                if (direct) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Lb.beam, Lb.violet))))
                        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (step == 2) Brush.horizontalGradient(listOf(Lb.beam, Lb.violet)) else Brush.horizontalGradient(listOf(Lb.line, Lb.line))))
                    }
                }
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (slideInHorizontally(tween(400)) { it / 3 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(200)))
                    },
                    label = "qr-step",
                ) { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (direct) Text("Step $s of 2", color = Lb.muted, fontSize = 13.sp)
                            Text(
                                when {
                                    s == 1 -> "Scan with the other phone's camera to join this phone's Wi-Fi"
                                    direct -> "Now scan this to open LanBeam in the browser"
                                    else -> "Scan with the other phone's camera to open LanBeam"
                                },
                                color = Lb.text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        val payload = if (s == 1) st.wifiQr else st.pairUrl
                        payload?.let { QrCard(it, if (s == 1) "QR code to join Wi-Fi ${st.hotspotName}" else "QR code to open LanBeam") { wake++ } }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            if (s == 1) TextLink(if (cantScan) "Hide Wi-Fi details" else "Can't scan? Show Wi-Fi name and password") { cantScan = !cantScan }
                            else {
                                // Right under the code it replaces, so it's clear what "new" means.
                                TextLink("New code") { Hub.newPairUrl() }
                                if (direct) TextLink("Back to step 1") { step = 1 }
                            }
                        }
                        if (s == 1 && cantScan) WifiDetails(st)
                        if (s == 2) { CodeCard(st); CertNote(st) }
                    }
                }
            }

            DevicesCard(st, direct, onPairAnother = if (paired && !showQr) ({ showQr = true }) else null)

            val active = st.incoming.filter { !it.done }
            if (active.isNotEmpty()) LiveHero(active, st.incoming)

            if (st.offers.isNotEmpty()) {
                SectionLabel("Shared from this phone")
                st.offers.forEach { o ->
                    FileRow(o.name, "${size(o.size)} · ${kind(o.name)}", kind(o.name), progress = null, done = false) {
                        QuietButton("Remove", small = true) {
                            runCatching { ctx.contentResolver.releasePersistableUriPermission(o.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            Hub.offers.removeIf { it.id == o.id }; Hub.publish()
                        }
                    }
                }
            }

            if (st.incoming.isNotEmpty()) {
                SectionLabel("Received")
                st.incoming.forEach { r ->
                    val frac = if (r.size == 0L) 1f else r.received.toFloat() / r.size
                    FileRow(
                        r.name,
                        if (r.done) "${size(r.size)} · saved to Download/LanBeam" else "${size(r.received)} of ${size(r.size)}",
                        kind(r.name), progress = if (r.done) null else frac, done = r.done,
                    ) {}
                }
            }

            st.advice?.let { Notice(it) }
        }

        // One main action at the bottom. It opens the file picker, and says so.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Lb.ink.copy(alpha = 0f), Lb.ink.copy(alpha = 0.92f), Lb.ink)))
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(Lb.text)
                    .clickable(role = Role.Button, onClickLabel = "Pick files to share") { pickFiles() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("plus", Lb.ink, 18); Spacer(Modifier.width(8.dp))
                Text("Share files from this phone", color = Lb.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DevicesCard(st: UiState, direct: Boolean, onPairAnother: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Lb.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", color = Lb.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            // Next to the devices it disconnects.
            if (st.peers.any { it.stage == PeerStage.PAIRED }) TextLink("Unpair all") { Hub.revokeAll() }
        }
        if (st.peers.isEmpty()) {
            Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile("phone", Lb.muted, Lb.surface2)
                Column(Modifier.weight(1f)) {
                    Text("Waiting for the other phone", color = Lb.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(if (direct) "Moves to step 2 by itself once it joins" else "Shows up once it opens the page", color = Lb.muted, fontSize = 13.sp)
                }
            }
        }
        st.peers.forEach { p ->
            val (label, color) = when {
                p.stage == PeerStage.PAIRED && p.pageOpen -> "Paired · page open" to Lb.ok
                p.stage == PeerStage.PAIRED -> "Paired · page closed" to Lb.muted
                p.stage == PeerStage.OPENED -> "Opened the page" to Lb.link
                else -> "Joined the Wi-Fi" to Lb.link
            }
            Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(when (p.kind) { "Mac", "PC" -> "laptop"; "iPad" -> "tablet"; else -> "phone" }, color, color.copy(alpha = 0.14f))
                Column(Modifier.weight(1f)) {
                    Text(p.kind, color = Lb.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(label, color = Lb.muted, fontSize = 13.sp)
                }
                if (p.stage == PeerStage.PAIRED) Row(
                    Modifier.clip(CircleShape).background(Lb.ok.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { Glyph("check", Lb.ok, 12); Text("Paired", color = Lb.ok, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        // Once paired the QR is tucked away; bring it back here to add another phone or laptop.
        if (onPairAnother != null) TextLink("＋ Pair another device", onPairAnother)
    }
}

@Composable
private fun LiveHero(active: List<IncomingView>, all: List<IncomingView>) {
    val speed = active.sumOf { it.bytesPerSec }
    val left = active.sumOf { it.size - it.received }
    val eta = if (speed > 0) (left / speed).toInt() else -1
    val doneCount = all.count { it.done }
    val total = all.sumOf { it.size }.coerceAtLeast(1)
    val got = all.sumOf { if (it.done) it.size else it.received }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.radialGradient(listOf(Lb.beam.copy(alpha = 0.20f), Lb.violet.copy(alpha = 0.08f), Lb.hero), radius = 700f))
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Receiving", color = Lb.text2, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("%.1f".format(speed / 1e6), color = Lb.text, fontSize = 54.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, style = TextStyle(fontFeatureSettings = tnum))
            Text("MB/s", color = Lb.text2, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(
            "${doneCount + 1} of ${all.size} files · ${size(left)} left" + if (eta >= 0) " · $eta s" else "",
            color = Lb.text2, fontSize = 13.sp, fontFamily = Mono, style = TextStyle(fontFeatureSettings = tnum),
        )
        Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.85f).height(6.dp).clip(CircleShape).background(Lb.line)) {
            Box(Modifier.fillMaxWidth((got.toFloat() / total).coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Lb.spectrum))
        }
    }
}

@Composable
private fun FileRow(name: String, sub: String, kind: String, progress: Float?, done: Boolean, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lb.surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(
                when (kind) {
                    "video" -> Brush.linearGradient(listOf(Color(0xFF2B3550), Color(0xFF3A2A4F)))
                    "photo" -> Brush.linearGradient(listOf(Color(0xFF24303D), Color(0xFF3B3A2A)))
                    else -> Brush.linearGradient(listOf(Lb.surface2, Lb.surface2))
                },
            ),
            contentAlignment = Alignment.Center,
        ) { Glyph(if (kind == "video") "play" else if (kind == "photo") "image" else "doc", Lb.text2, 18) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(name, color = Lb.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                if (progress != null) Text("${(progress * 100).toInt()}%", color = Lb.text2, fontSize = 12.sp, fontFamily = Mono, style = TextStyle(fontFeatureSettings = tnum))
            }
            if (progress != null) Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Lb.line)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Lb.beam))
            }
            Text(sub, color = Lb.muted, fontSize = 12.sp, style = TextStyle(fontFeatureSettings = tnum), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (done) Glyph("check", Lb.ok, 20)
        trailing()
    }
}

@Composable
private fun WifiDetails(st: UiState) {
    val pass = st.wifiQr?.let { Regex("""P:((?:\\.|[^;])*);""").find(it)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1") } ?: ""
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lb.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("On the other phone, open Wi-Fi settings and join:", color = Lb.muted, fontSize = 13.sp)
        Row { Text("Name", color = Lb.muted, fontSize = 13.sp, modifier = Modifier.width(80.dp)); Text(st.hotspotName ?: "", color = Lb.text, fontFamily = Mono, fontSize = 15.sp) }
        Row { Text("Password", color = Lb.muted, fontSize = 13.sp, modifier = Modifier.width(80.dp)); Text(pass, color = Lb.text, fontFamily = Mono, fontSize = 15.sp) }
    }
}

// ------------------------------------------------------------------ QR

/**
 * While a QR code is on screen: full brightness and the screen stays on, so it scans first time.
 * After 1 minute it drops back to normal (battery, OLED burn-in); a tap on the QR or a new step boosts again.
 */
@Composable
private fun QrMode(on: Boolean, wakeKey: Int) {
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    var boosted by remember { mutableStateOf(true) }
    LaunchedEffect(on, wakeKey) { boosted = true; kotlinx.coroutines.delay(60_000); boosted = false }
    val active = on && boosted
    DisposableEffect(active) {
        val w = activity.window
        if (active) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
}

@Composable
private fun QrCard(payload: String, description: String, onTap: () -> Unit) {
    val code = remember(payload) { StyledQr.encode(payload) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = 300.dp).fillMaxWidth(0.8f)
                .shadow(32.dp, RoundedCornerShape(28.dp), ambientColor = Lb.beam, spotColor = Lb.beam)
                .clip(RoundedCornerShape(28.dp)).background(Color.White)
                .border(1.dp, Lb.beam.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                .clickable(onClickLabel = "Brighten the screen", onClick = onTap).padding(16.dp),
        ) {
            StyledQr.Draw(code, Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = description })
        }
    }
}

/**
 * A QR code drawn in the LanBeam style: round dots in a deep blue-to-violet gradient, rounded
 * corner "eyes" with a beam-coloured centre, and the LanBeam mark in the middle. It is still a
 * standard QR code: dark on white with a quiet zone, and when the mark covers the middle the code
 * uses the highest error correction (H, 30 %) so the covered modules are rebuilt by the scanner.
 */
private object StyledQr {
    class Code(val n: Int, val dark: (Int, Int) -> Boolean, val logo: Boolean)

    private val dot1 = Color(0xFF16226A)   // deep blue  (~14:1 on white)
    private val dot2 = Color(0xFF4A1E7A)   // deep violet (~11:1 on white)
    private val eye = Color(0xFF0A0B10)
    private val eyeCore1 = Color(0xFF2F4FE0)
    private val eyeCore2 = Color(0xFF7A3FE0)

    fun encode(text: String): Code {
        val q = com.google.zxing.qrcode.encoder.Encoder.encode(text, ErrorCorrectionLevel.H)
        val m = q.matrix
        // From version 7 an alignment pattern sits in the centre; never cover it with the mark.
        return Code(m.width, { x, y -> m.get(x, y).toInt() == 1 }, logo = q.version.versionNumber < 7)
    }

    private fun inEye(x: Int, y: Int, n: Int) =
        (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)

    @Composable
    fun Draw(c: Code, modifier: Modifier) {
        Canvas(modifier) {
            val quiet = 2 // modules of white border inside the card (the card's own padding adds more)
            val cell = size.minDimension / (c.n + 2 * quiet)
            val o = quiet * cell
            val brush = Brush.linearGradient(listOf(dot1, dot2), Offset.Zero, Offset(size.width, size.height))
            val logoCells = if (c.logo) (c.n / 5).let { if (it % 2 == c.n % 2) it else it + 1 } else 0
            val lo = (c.n - logoCells) / 2
            // Data modules as dots.
            for (y in 0 until c.n) for (x in 0 until c.n) {
                if (!c.dark(x, y) || inEye(x, y, c.n)) continue
                if (logoCells > 0 && x in lo until lo + logoCells && y in lo until lo + logoCells) continue
                drawCircle(brush, radius = cell * 0.46f, center = Offset(o + (x + 0.5f) * cell, o + (y + 0.5f) * cell))
            }
            // The three finder "eyes": rounded ring + rounded core.
            for ((ex, ey) in listOf(0 to 0, c.n - 7 to 0, 0 to c.n - 7)) {
                val tl = Offset(o + ex * cell, o + ey * cell)
                drawRoundRect(eye, tl + Offset(cell / 2, cell / 2), Size(6 * cell, 6 * cell), CornerRadius(2.2f * cell), style = Stroke(width = cell))
                drawRoundRect(
                    Brush.linearGradient(listOf(eyeCore1, eyeCore2), tl + Offset(2 * cell, 2 * cell), tl + Offset(5 * cell, 5 * cell)),
                    tl + Offset(2 * cell, 2 * cell), Size(3 * cell, 3 * cell), CornerRadius(1.1f * cell),
                )
            }
            // The LanBeam logo in the middle, on a thin white ring so it stands clear of the dots.
            if (logoCells > 0) {
                val s = logoCells * cell
                val tl = Offset(o + lo * cell, o + lo * cell)
                drawRoundRect(Color.White, tl, Size(s, s), CornerRadius(s * 0.3f))
                val inset = cell * 0.5f
                drawLogo(tl + Offset(inset, inset), s - 2 * inset)
            }
        }
    }
}

// ------------------------------------------------------------------ logo

/** The LanBeam logo ("Fast swap"): two arrows passing each other with speed streaks, on the blue-violet tile. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLogo(tl: Offset, side: Float) {
    val u = side / 108f
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFF4F7DFF), Color(0xFF6A4DF5)), tl, tl + Offset(side, side)),
        tl, Size(side, side), CornerRadius(30 * u),
    )
    fun pt(x: Float, y: Float) = tl + Offset(x * u, y * u)
    val arrow = Stroke(width = 7 * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply { moveTo(pt(40f, 40f).x, pt(40f, 40f).y); lineTo(pt(80f, 40f).x, pt(80f, 40f).y)
        moveTo(pt(68f, 28f).x, pt(68f, 28f).y); lineTo(pt(80f, 40f).x, pt(80f, 40f).y); lineTo(pt(68f, 52f).x, pt(68f, 52f).y) }, Color.White, style = arrow)
    drawPath(Path().apply { moveTo(pt(68f, 68f).x, pt(68f, 68f).y); lineTo(pt(28f, 68f).x, pt(28f, 68f).y)
        moveTo(pt(40f, 56f).x, pt(40f, 56f).y); lineTo(pt(28f, 68f).x, pt(28f, 68f).y); lineTo(pt(40f, 80f).x, pt(40f, 80f).y) }, Color.White, style = arrow)
    if (side >= 40f) { // the streaks only where there is room for them, like the home-screen icon
        drawLine(Color.White.copy(alpha = 0.65f), pt(22f, 40f), pt(30f, 40f), 6 * u, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.65f), pt(78f, 68f), pt(86f, 68f), 6 * u, StrokeCap.Round)
    }
}

@Composable
private fun LogoMark(sizeDp: Int) {
    Canvas(Modifier.size(sizeDp.dp).semantics { contentDescription = "LanBeam" }) { drawLogo(Offset.Zero, size.minDimension) }
}

// ------------------------------------------------------------------ small parts

@Composable private fun MonoLabel(t: String) = Text(t, color = Lb.muted, fontSize = 11.sp, fontFamily = Mono, letterSpacing = 0.9.sp)

@Composable private fun SectionLabel(t: String) =
    Text(t, color = Lb.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

@Composable
private fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).background(Lb.text).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Lb.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun QuietButton(label: String, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(if (small) 36.dp else 40.dp).clip(CircleShape).background(Lb.surface2)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Lb.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Lb.text2, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    Text(
        label, color = Lb.link, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun Notice(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lb.amber.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph("warn", Lb.amber, 18)
        Text(text, color = Color(0xFFF1D6A8), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun IconTile(glyph: String, tint: Color, bg: Color) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) { Glyph(glyph, tint, 18) }
}

/** Hairline icons drawn on a 24-unit grid, so there are no icon files to ship. */
@Composable
private fun Glyph(name: String, tint: Color, sizeDp: Int) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val s = size.minDimension / 24f
        val sw = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(block: Path.() -> Unit) = Path().apply(block)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), 1.7f * s, StrokeCap.Round)
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
            drawArc(tint, start, sweep, false, Offset((cx - r) * s, (cy - r) * s), Size(2 * r * s, 2 * r * s), style = sw)
        when (name) {
            "wifi" -> { arc(12f, 20f, 15f, 225f, 90f); arc(12f, 20f, 10f, 225f, 90f); arc(12f, 20f, 5f, 225f, 90f); drawCircle(tint, 1.1f * s, Offset(12 * s, 20 * s)) }
            "direct" -> {
                drawRoundRect(tint, Offset(2.5f * s, 5 * s), Size(6 * s, 14 * s), CornerRadius(1.5f * s), style = sw)
                drawRoundRect(tint, Offset(15.5f * s, 5 * s), Size(6 * s, 14 * s), CornerRadius(1.5f * s), style = sw)
                drawLine(Lb.beam, Offset(9.5f * s, 12 * s), Offset(14.5f * s, 12 * s), 2.2f * s, StrokeCap.Round)
            }
            "phone" -> { drawRoundRect(tint, Offset(6.5f * s, 2.5f * s), Size(11 * s, 19 * s), CornerRadius(2.5f * s), style = sw); line(10.5f, 18.5f, 13.5f, 18.5f) }
            "laptop" -> { drawRoundRect(tint, Offset(4 * s, 5 * s), Size(16 * s, 11 * s), CornerRadius(1.5f * s), style = sw); line(2f, 19.5f, 22f, 19.5f) }
            "tablet" -> { drawRoundRect(tint, Offset(4.5f * s, 2.5f * s), Size(15 * s, 19 * s), CornerRadius(2.5f * s), style = sw); line(10.5f, 18.5f, 13.5f, 18.5f) }
            "lock" -> { drawRoundRect(tint, Offset(5 * s, 11 * s), Size(14 * s, 10 * s), CornerRadius(2 * s), style = sw); arc(12f, 8f, 4f, 180f, 180f); line(8f, 8f, 8f, 11f); line(16f, 8f, 16f, 11f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "check" -> drawPath(p { moveTo(5 * s, 12.5f * s); lineTo(9.5f * s, 17 * s); lineTo(19 * s, 7.5f * s) }, tint, style = Stroke(2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            "warn" -> { drawPath(p { moveTo(12 * s, 3 * s); lineTo(21.5f * s, 20 * s); lineTo(2.5f * s, 20 * s); close() }, tint, style = sw); line(12f, 10f, 12f, 14f); drawCircle(tint, 0.9f * s, Offset(12 * s, 17 * s)) }
            "play" -> drawPath(p { moveTo(8 * s, 6.5f * s); lineTo(17 * s, 12 * s); lineTo(8 * s, 17.5f * s); close() }, tint)
            "image" -> { drawRoundRect(tint, Offset(3 * s, 5 * s), Size(18 * s, 14 * s), CornerRadius(2 * s), style = sw); drawPath(p { moveTo(4 * s, 17 * s); lineTo(9 * s, 12 * s); lineTo(13 * s, 16 * s); lineTo(16 * s, 13 * s); lineTo(20 * s, 17 * s) }, tint, style = sw) }
            else -> { drawPath(p { moveTo(6 * s, 2.5f * s); lineTo(14 * s, 2.5f * s); lineTo(18 * s, 6.5f * s); lineTo(18 * s, 21.5f * s); lineTo(6 * s, 21.5f * s); close() }, tint, style = sw); drawPath(p { moveTo(14 * s, 2.5f * s); lineTo(14 * s, 6.5f * s); lineTo(18 * s, 6.5f * s) }, tint, style = sw) }
        }
    }
}

private fun size(b: Long): String {
    val u = listOf("B", "KB", "MB", "GB", "TB"); var n = b.toDouble(); var i = 0
    while (n >= 1000 && i < u.size - 1) { n /= 1000; i++ }
    return if (i == 0) "$b B" else "%.1f %s".format(n, u[i])
}

private fun kind(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mov", "mp4", "m4v", "mkv", "webm", "avi", "3gp" -> "video"
    "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng" -> "photo"
    else -> "file"
}

/** For laptops and anything without a camera: the address to type, and a one-time 6-digit code. */
@Composable
private fun CodeCard(st: UiState) {
    if (st.codeLocked) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Lb.amber.copy(alpha = 0.08f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Code pairing paused", color = Lb.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Someone on this network entered too many wrong codes. The QR code above still works.", color = Lb.text2, fontSize = 13.sp, lineHeight = 18.sp)
            QuietButton("Allow codes again") { Hub.unlockCodes() }
        }
        return
    }
    val code = st.pairCode ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Lb.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No camera? On a laptop, open", color = Lb.muted, fontSize = 13.sp)
        Text(st.baseUrl ?: "", color = Lb.text, fontSize = 15.sp, fontFamily = Mono)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("and enter", color = Lb.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                code.chunked(3).joinToString(" "), color = Lb.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = Mono, letterSpacing = 2.sp, style = TextStyle(fontFeatureSettings = tnum),
                modifier = Modifier.semantics { contentDescription = "Pairing code ${code.toList().joinToString(" ")}" },
            )
        }
        Text("The QR and the code each work once.", color = Lb.muted, fontSize = 12.sp)
    }
}

/**
 * This Android's certificate fingerprint. Browsers warn about a self-signed certificate; someone careful
 * can open the warning's certificate details and check the SHA-256 starts with the same digits, which
 * proves they reached this phone and not an impostor on the same Wi-Fi.
 */
@Composable
private fun CertNote(st: UiState) {
    val fp = st.certFp?.takeIf { it.isNotBlank() } ?: return
    val bytes = fp.split(" ")
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lb.surface)
            .clickable(role = Role.Button, onClickLabel = "Show the full fingerprint") { open = !open }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph("lock", Lb.muted, 14)
            Text("Android's certificate (SHA-256)", color = Lb.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (open) "Less" else "Full", color = Lb.link, fontSize = 12.sp)
        }
        // At least 16 of the 32 bytes, always visible, grouped like Safari and Chrome show it.
        val shown = if (open) bytes else bytes.take(16)
        shown.chunked(8).forEach { row ->
            Text(row.joinToString(" "), color = Lb.text, fontSize = 13.sp, fontFamily = Mono, letterSpacing = 0.5.sp)
        }
        Text(
            "On a shared Wi-Fi, compare these with the SHA-256 in the browser's warning (certificate details). " +
                "All of them must match. If they don't, stop and use Direct.",
            color = Lb.muted, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}
