# Nova: Android uchun ovozli yordamchi (JARVIS uslubida)

Ism aytilganda ekranda orb chiqadi. Nova telefonni boshqaradi, o'zbek tilida suhbatlashadi, diagnostika va xavfsizlik tekshiruvlarini bajaradi. Oflayn ham ishlaydi.

Kod: `app/src/main/java/com/example/nova/` ichida `Nova.kt` (asosiy ilova) va `Extras.kt` (xavfsizlik, kuzatuv, salomatlik).

## Imkoniyatlar

| Toifa | Buyruq misollari |
|---|---|
| Diagnostika | diagnostika, to'liq diagnostika, batareya, xotira, ram, internet |
| Faollik jurnali | telefon qachon ochilgan, qaysi ilovalar ishlatilgan (ekran vaqti) |
| Xavfsizlik | xavfsizlik tekshiruvi, ruxsat tekshiruvi (kamera+mikrofon ilovalari), ovozimni o'rgan, ovoz qulfini yoq |
| Blok | blokni och (ekranni yoqadi, PIN/barmoq izini o'zingiz tasdiqlaysiz) |
| Wi-Fi | wifi qurilmalar, qurilmalarga ishonaman (yangi qurilma ogohlantirishi), router |
| Kameralar | kameralarni qidir, kamerani och <nom> (o'z IP kameralaringiz), qo'riqlashni boshla/to'xtat (telefon kamerasi) |
| Salomatlik | puls (kamera+chiroq), qadam, bmi 70 175, suv ichish |
| Favqulodda | SOS (joylashuv bilan SMS tayyorlaydi), joylashuvim |
| Sayt | kundalik (sayt ochib skrinshot oladi) |
| Qidiruv | qidir ...: 3 ta natija kartochka (DuckDuckGo, Vikipediya, Bing) |
| Ma'lumot | ob-havo, namoz vaqti, valyuta kursi, yangiliklar, X haqida ma'lumot |
| Tizim | orqaga, bosh ekran, skrinshot, qulfla, pastga, yuqoriga, bos <matn>, yoz <matn> |
| Qurilma | chiroq, ovoz, wifi/bluetooth sozlamalari, kamera, musiqa boshqaruvi |
| Aloqa | qo'ng'iroq qil Ali, sms Ali salom, kontakt Ali |
| Reja | budilnik, taymer, eslatib qo'y, eslatma, xarajat, pomodoro, kalendarga qo'sh |
| AI | boshqa hamma savol (OpenRouter) |

To'liq ro'yxat ilovada: Menyu > Buyruqlar ro'yxati.

## O'rnatish
1. Repozitoriyni klon qiling, Android Studio'da oching (Android 8+). Yoki GitHub Actions APK yig'adi (Actions > nova-debug-apk).
2. Ilovani o'rnating, ruxsatlarni bering (mikrofon, kamera, joylashuv, faollik).
3. Menyu > Sozlamalar: OpenRouter kaliti, favqulodda raqam, IP kameralar.
4. Menyu > Oflayn modelni yuklash. Ixtiyoriy: "Ovoz tanish modelini yuklash" (egasi ovozi uchun).
5. Menyu: Orb ruxsati, Boshqaruv ruxsati, Foydalanish statistikasi ruxsati, Batareya cheklovi.
6. "Yoqish" va "Nova" deng.

Modellar haqida: `MODEL.md`. Maxfiylik: `PRIVACY.md`. Xavfsizlik: `SECURITY.md`.

## Muhim cheklovlar (halol ma'lumot)
- **Blokni ochish:** Android PIN/barmoq izini aylanib o'tishga ruxsat bermaydi. Nova ekranni yoqadi va tizim oynasini chiqaradi, tasdiqlash sizda.
- **Ovozdan tanish:** qulaylik uchun himoya. Yozib olingan ovoz bilan aldash mumkin, shuning uchun to'liq xavfsizlik deb hisoblamang.
- **Yuz:** faqat yuz bor/yo'qligi aniqlanadi (qo'riqlashda). Kim ekanini aniqlash yo'q.
- **Puls:** kamera orqali taxminiy o'lchov, tibbiy asbob emas.
- **Wi-Fi skaner:** ba'zi qurilmalar so'rovga javob bermaydi, ro'yxat to'liq bo'lmasligi mumkin.
- **Kamera fonda:** Android fonda kamerani cheklaydi. Nova'ni ilovadan ochib "Yoqish" bosing.
- **Qidiruv:** DuckDuckGo/Bing sahifalarini o'qiydi, ular o'zgarsa `Search` bo'limini yangilang.

## Hissa qo'shish
`CONTRIBUTING.md` ga qarang. Litsenziya: MIT.
