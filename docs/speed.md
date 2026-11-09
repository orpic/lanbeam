# Speed: measurements and levers

Goal: use the full potential of the Wi-Fi link in every mode (2.4 GHz, 5 GHz, Android hotspot). Measure before and after every change.

## Hardware ceilings
- Test Android phone: Wi-Fi 5 (802.11ac), 2x2, up to 866 Mbps link (VHT80).
- Test iPhone: Wi-Fi 6, 2x2, up to ~1200 Mbps link.
- Home router (dual band): one 2.4 GHz and one 5 GHz network. The test laptop gets 433 Mbps on 5 GHz.
- Real TCP throughput is ~65-75 % of link rate. Through a router, phone-to-phone uses the same radio twice (about half).

## Measurements (laptop on 5 GHz -> router -> Android), TLS 1.3, 8 MiB chunks

### 2026-11-09, Android on 2.4 GHz, 802.11n, 144 Mbps link (= 18 MB/s raw)
| Direction | Connections | MB/s |
|---|---|---|
| laptop -> Android (upload) | 1 | 9.8-10.6 |
| | 2 | 12.5 |
| | 4 | 12.0-12.5 |
| | 8 | 12.2 |
| | 16 | 9.4 |
| | 32 | 12.5 |
| | 64 | 11.7 |
| Android -> laptop (download) | 1 | 13.5 |
| | 4 | 12.3 |
| | 16 | 12.0 |

Conclusion: ~12.5 MB/s is the 2.4 GHz link ceiling (~70 % of 144 Mbps). More than 2-4 connections gives nothing. Many parts help a download manager over the internet (long latency, per-connection server limits), not on a LAN with ~3 ms latency.

### 2026-11-09, Android on the router's 5 GHz network, 802.11ac, 866 Mbps link; laptop 433 Mbps
| Direction | Connections | MB/s |
|---|---|---|
| laptop -> Android (upload) | 1 / 2 / 4 / 8 | 17.6 / 17.2 / 18.2 / 17.5 |
| Android -> laptop (download) | 1 / 2 / 4 / 8 | 15.6 / 16.0 / 16.5 / 16.6 |

Both clients share one router radio (two air hops), and the laptop's 433 Mbps link is the slower end: the expected ceiling is ~23 MB/s, so ~75-80 % of it. Parallel connections add almost nothing on a LAN.

### Android hotspot (Direct mode): band actually used
`dumpsys wifi` -> `mCurrentSoftApInfoMap` (SoftApInfo width: 2 = 20 MHz, 3 = 40, 4 = 80, 6 = 160).
| the Android's own Wi-Fi connection | Requested | Hotspot came up on |
|---|---|---|
| 2.4 GHz router, channel 8 | 5 GHz | 2447 MHz (ch 8), 20 MHz, Wi-Fi 4 (forced onto the router's channel) |
| 5 GHz router, channel 36 | 5 GHz | 5180 MHz (ch 36), 80 MHz, Wi-Fi 5 |
| not connected | 5 GHz | _pending_ |

The chip runs the hotspot on the same channel as its Wi-Fi connection. So when the Android is on a 2.4 GHz network, Direct mode is stuck on slow 2.4 GHz 20 MHz; the app must tell the user to disconnect (or join 5 GHz) first.

### 2026-11-09, iPhone (browser) <-> Android, Direct mode (hotspot 5 GHz ch 36, 80 MHz), in-page speed test from memory
| Connections | Upload iPhone -> Android | Download Android -> iPhone |
|---|---|---|
| 4 (256 MB each way) | 46.6 MB/s | 79.1 MB/s |
| 6 (384 MB each way) | 59.5 MB/s | 79.5 MB/s |

Download 79.5 MB/s = 636 Mbps, ~73 % of the the Android's 866 Mbps link: at the radio ceiling. Upload still rises with connections (46.6 -> 59.5), so the browser's upload path is the limit, not the radio. WebKit caps a host at 6 connections; more needs a second port (separate connection pool). Direct mode is ~4.5x Same Wi-Fi through the router.

## Levers, ranked (from research, 2026-11-09)
1. **Direct link, 5 GHz, 80 MHz (Android hotspot).** Biggest gain, 3-5x. Android 16 (API 36) adds public `WifiManager.startLocalOnlyHotspotWithConfiguration` + `SoftApConfiguration.Builder.setChannels` (5 GHz band, channel 0 = auto). Needs `CHANGE_WIFI_STATE` + `NEARBY_WIFI_DEVICES`. Verify the phone honors it; read back the config and the link rate. Reported real-world direct transfers: ~42-55 MB/s.
2. **Android-to-Android: Wi-Fi Direct** with `WifiP2pConfig.Builder.setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)`. iPhone cannot use Wi-Fi Direct; use the hotspot for iPhones. Wi-Fi Aware needs iPhone 12+.
3. **Router mode: both phones on 5 GHz**, 2-4x over 2.4 GHz. App should read `WifiInfo.getFrequency()` / link speed and tell the user to switch to 5 GHz or use hotspot mode.
4. **Keep alive on aggressive battery managers (some Android skins):** foreground service (done), keep screen on during transfer, battery "No restrictions" + Autostart. `FULL_HIGH_PERF` lock is deprecated -> `FULL_LOW_LATENCY`, which only applies while foreground with screen on and connected to an AP. A/B test the lock.
5. **Server I/O and TLS:** leave socket buffers on autotuning (setting SO_RCVBUF disables it; current code sets 4 MB, A/B test removing it). Prefer AES-128-GCM (hardware AES on ARMv8). A/B test plain HTTP vs TLS at 5 GHz speeds to measure TLS cost.
6. **Browser (iPhone):** WebKit allows ~6 HTTP/1.1 connections per host, so 2-4 parallel 8 MiB slices. Keep `File.slice` + PUT (streaming request bodies unsupported). Multi-GB downloads can stall; support Range resume, keep screen on.
7. **iPhone on a network without internet:** iOS may offer to switch to cellular or another saved network. Address the server by IP; tell the user to stay on the Wi-Fi.

## Sources
- [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager), [SoftApConfiguration.Builder](https://developer.android.com/reference/android/net/wifi/SoftApConfiguration.Builder), [WifiP2pConfig.Builder](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder)
- [Quick Share large file test (PhoneArena)](https://www.phonearena.com/news/samsung-galaxy-s20-note-20-ultra-quick-share-large-file-video-transfer_id122661), [Quick Share Wi-Fi Direct (Android Authority)](https://www.androidauthority.com/quick-share-for-windows-wifi-direct-3494451)
- [Client-to-client halving (MikroTik)](https://forum.mikrotik.com/t/audience-wireless-speed/144673), [Emby wireless backhaul](https://emby.media/learn/articles/networking/performance/wireless/Network-Wireless-Backhaul.html)
- [Cloudflare TCP tuning](https://blog.cloudflare.com/optimizing-tcp-for-high-throughput-and-low-latency), [Cloudflare ChaCha on mobile](https://blog.cloudflare.com/do-the-chacha-better-mobile-performance-with-cryptography/)
- [LocalSend README](https://github.com/localsend/localsend)
- [WebKit slow HTTPS upload 251328](https://bugs.webkit.org/show_bug.cgi?id=251328), [WebKit streaming body 167145](https://bugs.webkit.org/show_bug.cgi?id=167145)
- [Android background limits (AdGuard)](https://adguard.com/kb/adguard-for-android/solving-problems/background-work/), [Apple: no-internet Wi-Fi](https://developer.apple.com/forums/thread/789103), [Wi-Fi Aware (Apple)](https://developer.apple.com/documentation/wifiaware.md)
