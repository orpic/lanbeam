<p align="center"><img src="docs/icon.svg" width="96" alt="LanBeam icon"></p>

<h1 align="center">LanBeam</h1>

<p align="center">Fast, private file transfer between an Android phone and any phone or laptop nearby.<br>
Local Wi-Fi only. No internet, no cloud, no account.</p>

---

LanBeam turns an Android phone into a small, encrypted file server for the devices next to it.
The other side needs **no app**: an iPhone, another Android phone, a Mac or a PC just opens a page
in its browser. Files go straight from one device to the other over Wi-Fi and never leave the room.

## Features

- **Fast.** Up to about **80 MB/s** in Direct mode (a 1 GB video in roughly 15 seconds), using many
  parallel connections and writing pieces straight into place, so there is no waiting to "join" files at the end.
- **Direct mode.** The Android makes its own 5 GHz Wi-Fi for the other phone. No router needed.
- **Same Wi-Fi mode.** Both devices stay on your normal network and keep their internet.
- **Private.** Everything is encrypted (TLS 1.3). Only a device that scanned the one-time QR code
  (or typed the one-time 6-digit code) can see or send anything. Nothing ever touches the internet.
- **Both ways.** Send from the browser to the Android, and offer files from the Android to the browser.
- **Originals.** On iPhone, files picked through *Choose File* arrive byte-for-byte unchanged (HEIC/HEVC).
- **Survives hiccups.** Transfers pause when the connection drops and continue where they stopped.

## Install

1. On the Android phone, download the latest **`LanBeam-x.y.z.apk`** from the
   [Releases](../../releases) page.
2. Open it and allow *Install unknown apps* for your browser or file manager when Android asks.
   To check the download is genuine, see [Verify the download](#verify-the-download).
3. Requires **Android 10 or newer**. Direct mode at full 5 GHz speed needs **Android 16**; on older
   versions Android chooses the hotspot band (often the slower 2.4 GHz).

The other device needs nothing installed.

## Use

### Direct (fastest)

1. Open LanBeam on the Android, choose **Direct**, tap **Start sharing**.
2. On the other phone, scan **QR 1** with the camera and join the Wi-Fi it offers.
   The Android notices and switches to **QR 2** by itself.
3. Scan **QR 2**. The LanBeam page opens, already paired.
4. The browser warns that the connection is not private (see below). Continue once.
5. Pick files and tap **Send**, or tap **Save** on files the Android offers.

### Same Wi-Fi

Both devices on the same network: choose **Same Wi-Fi**, start sharing, scan the QR code.

### Laptops and devices without a camera

Under the page QR, the Android shows an address like `https://10.x.x.x:47201` and a **6-digit code**.
Open the address on the laptop and enter the code. The code works once and expires after 10 minutes.

### The "not private" warning

LanBeam encrypts everything, but it runs without internet, so it cannot get a certificate from a public
authority. Browsers show a warning the first time:

- **Safari (iPhone/Mac):** *Show Details* → *visit this website* → *Visit Website*.
- **Chrome:** *Advanced* → *Proceed to …*.

This is expected, and the connection is still encrypted. What the warning means is that the browser
can't confirm *which* device it is talking to. That matters only on a Wi-Fi you share with strangers:

- **Direct mode** is safe by design: only devices that scanned QR 1 (the hotspot's random password) are on
  that network at all.
- **On a shared Wi-Fi**, check it's really your Android: under the page QR, LanBeam shows
  **Android's certificate** (tap it for the full fingerprint). In the browser's warning, open the certificate
  details (*Show Details* → certificate in Safari, the warning text → certificate viewer in Chrome) and check
  the **SHA-256** starts with the same digits. If it doesn't, stop and use Direct instead.

Each Android creates its own certificate on first use; it never leaves the phone.

### Tips

- **iPhone originals:** in the picker menu tap **Choose File** (Files). *Photo Library* sends converted copies
  (H.264 / JPEG). To get photos into Files: Photos → Share → *Save to Files*.
- **iPhone jumps back to home Wi-Fi when locked:** iOS prefers networks with internet. While sharing,
  turn off *Auto-Join* for your home Wi-Fi (Settings → Wi-Fi → ⓘ). The page keeps the screen awake while open.
- **Keep the page open** during large transfers; browsers pause background tabs.

## How it works

- A native Android app (Kotlin, Jetpack Compose) runs an HTTPS/1.1 server bound only to the Wi-Fi or hotspot
  address, never to mobile data.
- A self-signed certificate (EC P-256) is created on the phone. Pairing exchanges a one-time token or code for a
  session cookie (HttpOnly, Secure, SameSite=Strict). Devices outside the local subnet are refused.
- The page splits each file into 16 MiB pieces and sends them over up to 18 parallel connections
  (6 per port × 3 ports). The Android writes every piece directly at its offset in the final file.
- Direct mode uses Android's local-only hotspot, requesting the 5 GHz band.
- Received files are saved to **Download/LanBeam**.

Measurements and the reasoning behind the speed work are in [docs/speed.md](docs/speed.md).

## Build from source

The source is public for transparency. You may build it unmodified for your own personal use; see the
[License](#license) for what is and isn't allowed.

Requirements: JDK 17+ (21 recommended) and the Android SDK (platform 36).

```bash
cd android
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed only if signing details are set in `~/.gradle/gradle.properties`
(never in the repo):

```properties
LANBEAM_STORE_FILE=/path/to/your-release.jks
LANBEAM_STORE_PASSWORD=...
LANBEAM_KEY_ALIAS=...
LANBEAM_KEY_PASSWORD=...
```

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

## Privacy

LanBeam has no analytics, no ads, no accounts and no internet access of its own. Files, names and codes stay on
the two devices involved. The Android forgets all pairings when sharing stops.

## Status

Early but working (v0.1.1). Tested with an Android 16 phone and an iPhone on iOS 27.
Issues and suggestions are welcome.

## Verify the download

Every release APK is signed with the LanBeam release key. Its certificate SHA-256 fingerprint is:

```
D0:04:43:D6:06:D1:8B:27:98:DB:CE:C9:4E:69:61:1B:57:24:E5:21:01:DE:5F:86:07:02:EF:BA:A1:FD:8A:6E
```

Check a downloaded APK with the Android SDK's `apksigner`:

```bash
apksigner verify --print-certs LanBeam-x.y.z.apk
```

The `Signer #1 certificate SHA-256 digest` must match the fingerprint above (without the colons, lower case).
Each release's notes also list the APK file's own SHA-256.

## License

Proprietary, free for **personal, non-commercial use**. No redistribution (share the link to this
repository instead), no modification or derivative works. The source is visible for transparency only.
Full terms in [LICENSE](LICENSE).
