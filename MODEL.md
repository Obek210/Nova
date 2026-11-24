# Yaxshiroq ovoz modelini ulash

Nova **Vosk (Kaldi)** formatidagi modellarni ishlatadi. Model papkasi ichida `am`, `conf`, `graph` (va ko'pincha `ivector`) bo'lishi kerak.

## 1. Modelni GitHub Release orqali tarqatish (avtomatik yangilanish)
1. Model papkasini zip qiling (papkaning o'zi yoki ichidagi fayllar, ikkalasi ham bo'ladi).
2. GitHub repozitoriyingizda **Releases > Draft a new release** ni oching, teg bering (masalan `v1`), zip'ni "assets" ga yuklang va e'lon qiling.
3. Nova'da **Menyu > Sozlamalar > GitHub repozitoriy** maydoniga `egasi/nomi` yozing.
4. **Menyu > GitHub'dan model yangilash** (yoki "modelni yangila" deng). Nova oxirgi relizdagi birinchi `.zip` ni yuklab, faol qiladi.
5. Yangi reliz chiqarsangiz (`v2`), shu tugma yangisini tortadi. Eski modellar saqlanadi, **Menyu > Modelni tanlash** da almashtirasiz.

## 2. Boshqa yo'llar
- **Menyu > Modelni URL dan yuklash:** istalgan zip havolasi.
- **Menyu > O'z ovoz modelimni qo'shish (zip):** telefondagi fayldan.

## Maslahat
- Kichik model (~50 MB) tez, lekin aniqligi past. Katta model aniqroq, lekin ko'proq xotira va vaqt oladi.
- O'z modelingizni o'rgatish uchun Vosk/Kaldi qo'llanmalaridan foydalaning va o'zbek nutq korpuslarini (masalan, Common Voice) qo'shing.
