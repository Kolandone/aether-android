# Aether Android

Android client for [Aether](https://github.com/CluvexStudio/Aether) — a censorship circumvention tool that creates encrypted tunnels via MASQUE, WireGuard, or WARP-in-WARP (Gool) protocols.

## Features

- **Three protocols**: MASQUE (HTTP/3), WireGuard, Gool (double-layer WG)
- **5 scan modes**: Turbo, Balanced, Thorough, Stealth, Ironclad
- **6 obfuscation profiles**: Firewall, GFW, Balanced, Aggressive, Light, Off
- **MASQUE options**: H2 transport, ClientHello fragmentation, ECH
- **WireGuard options**: Keepalive, peer forcing, profile retry control
- **All Aether CLI flags** exposed in the settings UI
- **Dark theme** with connection animations
- **Background service** with notification stop button
- **Per-app proxy** via SOCKS5 on `127.0.0.1:1819`

## Download

Download the latest APK from [Releases](https://github.com/Kolandone/aether-android/releases).

## Build from Source

```bash
git clone https://github.com/Kolandone/aether-android.git
cd aether-android
./gradlew assembleRelease
```

The build requires:
- Android SDK (API 34)
- JDK 17
- The Aether binary (`libaether.so`) is downloaded automatically by GitHub Actions

## Configuration

All settings can be configured through the app's Settings page:

| Setting | Options |
|---------|---------|
| Protocol | MASQUE, WireGuard, Gool |
| Scan Mode | turbo, balanced, thorough, stealth, ironclad |
| IP Version | IPv4, IPv6, Both |
| Obfuscation | firewall, gfw, off, balanced, aggressive, light |
| MASQUE Transport | H3 (QUIC), H2 (TCP) |
| ECH | off, auto, base64 |
| Fragment | Enable/disable + size/delay |

## Links

- [Telegram](https://t.me/kolandjs1)
- [GitHub](https://github.com/Kolandone)
- [Aether Core](https://github.com/CluvexStudio/Aether)

## License

MIT

---

Made with ❤️ by [Koland](https://github.com/Kolandone)
