# Aether Android

کلاینت اندروید [Aether](https://github.com/CluvexStudio/Aether) — ابزار دور زدن سانسور که تونل‌های رمزنگاری‌شده از طریق پروتکل‌های MASQUE، WireGuard یا WARP-in-WARP (Gool) می‌سازه.

## قابلیت‌ها

- **سه پروتکل**: MASQUE (HTTP/3)، WireGuard، Gool (WG دو لایه)
- **۵ حالت اسکن**: Turbo, Balanced, Thorough, Stealth, Ironclad
- **۶ پروفایل استتار**: Firewall, GFW, Balanced, Aggressive, Light, Off
- **تنظیمات MASQUE**: انتقال H2، فرگمنت ClientHello، ECH
- **تنظیمات WireGuard**: Keepalive، اجبار Peer، کنترل retry
- **تمام پرچم‌های CLI Aether** در صفحه تنظیمات
- **تم تاریک** با انیمیشن اتصال
- **سرویس بک‌گراند** با دکمه توقف در نوتیفیکیشن
- **پروکسی per-app** از طریق SOCKS5 روی `127.0.0.1:1819`

## دانلود

آخرین نسخه APK رو از [Releases](https://github.com/Kolandone/aether-android/releases) دانلود کنید.

## ساخت از منبع

```bash
git clone https://github.com/Kolandone/aether-android.git
cd aether-android
./gradlew assembleRelease
```

نیازمندی‌ها:
- Android SDK (API 34)
- JDK 17
- باینری Aether (`libaether.so`) توسط GitHub Actions خودکار دانلود میشه

## تنظیمات

تمام تنظیمات از صفحه Settings قابل پیکربندی هستن:

| تنظیم | گزینه‌ها |
|--------|---------|
| پروتکل | MASQUE, WireGuard, Gool |
| حالت اسکن | turbo, balanced, thorough, stealth, ironclad |
| نسخه IP | IPv4, IPv6, Both |
| استتار | firewall, gfw, off, balanced, aggressive, light |
| انتقال MASQUE | H3 (QUIC), H2 (TCP) |
| ECH | off, auto, base64 |
| فرگمنت | فعال/غیرفعال + اندازه/تأخیر |

## لینک‌ها

- [تلگرام](https://t.me/kolandjs1)
- [گیتهاب](https://github.com/Kolandone)
- [هسته Aether](https://github.com/CluvexStudio/Aether)

## لایسنس

MIT

---

ساخته شده با ❤️ توسط [Koland](https://github.com/Kolandone)
