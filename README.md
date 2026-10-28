# Мандат · Mandat

Приватний офлайн-органайзер для Android: справи, люди, календар, доручення й обіцянки в одному зашифрованому місці. Українська та англійська. Безкоштовно, без реклами й акаунтів.

> **Статус: тестова версія.** Помилки й побажання: [Issues](../../issues).

## Особливості

- 🔒 **Приватність.** Дозволу на інтернет немає, дані лише на телефоні й завжди зашифровані (SQLCipher). Вхід відбитком або PIN-кодом (за бажанням лише PIN, і тоді ключ сам не приймає відбитка), автоблокування, знімки екрана заборонені. Копії, експорт і зміна захисту підтверджуються PIN телефона. За бажанням — нейтральна назва й іконка. Резервна копія лише з паролем (Argon2id + AES-256).
- 🗂️ **Справи й увага.** Фокус, фон і сплячі справи, наступний крок, застряглі й без кроку, розбір давніх прострочених.
- 👥 **Люди.** Свої категорії, позиції в справах, команда, розмова віч-на-віч, довідник контактів з імпортом .vcf і CSV.
- 📅 **Календар і час.** Місяць, тиждень із сіткою годин, список; щільність тижня з вільними вікнами; строки, доручення й обіцянки на одній стрічці.
- 📊 **Наочність.** Зведення за тиждень, місяць, рік; кожне число відкриває записи, з яких воно складається. Кольори завжди з підписом чи відсотком.
- 🧳 **Режим поїздки й лінза.** Позначені справи й усе пов'язане з ними (люди, записи щоденника, громади) зникають з екранів, пошуку, ШІ й експорту. Вимкнути режим можна лише відбитком чи PIN телефона; сховане себе не видає.
- 📤 **Експорт і імпорт.** .vcf, .ics, CSV, Markdown, PDF, сховище для Obsidian, пакет справ для колеги; за будь-який період. За замовчуванням зашифрований файл Мандата, ZIP з паролем — для інших застосунків.
- 🔭 **Ретроспектива.** Напрямки, пріоритети минулого й динаміка за місяцями з журналу змін.
- ✍️ **Тексти.** Листи, виступи, колонки, записки з шаблонами, версіями й експортом у Word і PDF.
- 📓 **Щоденник, бюджет, вхідні.**
- 🎭 **Демо.** Вигаданий власник із великою базою, щоб спробувати все без своїх даних; нічого не зберігається.
- 🤖 **Локальний ШІ (Gemma), за бажанням:** розкладає вставлений текст на справи й доручення, пише бриф і підсумок, скорочує виступ. Усе на телефоні, лише пропозиції.

## Встановлення

Завантажте `.apk` з [Releases](../../releases/latest). Для автооновлень — [Obtainium](https://obtainium.imranr.dev/). Перед оновленням радимо зробити резервну копію.

ШІ (необов'язково): Опції → «ШІ» → «Завантажити модель» (≈2,6 ГБ) → «Обрати файл моделі». Файл перевіряється за контрольною сумою.

## Прозорість

- **Дозволи:** сповіщення й перезапуск нагадувань після ввімкнення телефона; читання календаря телефона — лише якщо ви ввімкнете його показ. Збірка зупиняється, якщо з'являється дозвіл на інтернет.
- **Шифрування:** не шифруються лише налаштування вигляду й безпеки (тема, «Лише PIN», знімки екрана, автоблокування, нейтральна іконка), вмикачі нагадувань і календаря телефона, позначка «Мандат щось скопіював у буфер» (без тексту), технічні позначки й файл моделі ШІ (без ваших даних) і дати, на які є нагадування (щоб сповіщення приходили без розблокування; у тексті сповіщення нічого немає). Android також пам'ятає вибрану теку автокопій. Відкритий файл експорту — лише за вашим вибором і з попередженням.
- **Збірка:** APK збирається з цього коду в GitHub Actions окремо від тестів і підписується лише після схвалення автора. Контрольні суми залежностей і самого Gradle перевіряються; SHA-256 кожного APK опубліковано в релізі.
- **Межі:** не призначено для відомостей з грифом — для них потрібні спеціально захищені системи, а не застосунок на особистому телефоні. Режим поїздки не захищає від того, хто змусить відкрити Мандат і підтвердити вимкнення; резервні копії містять усе.
- **Як створено:** код написано за допомогою ШІ (Claude, Anthropic) під керівництвом і з тестуванням автора.

Kotlin · Jetpack Compose · Room · SQLCipher · [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Сторонні матеріали — [NOTICE.md](NOTICE.md). Ліцензія GPL-3.0.

---

# Mandat · English

A private, offline organiser for Android: matters, people, calendar, delegations and promises in one encrypted place. Ukrainian and English. Free, no ads, no accounts.

> **Status: test version.** Bugs and wishes: [Issues](../../issues).

## Features

- 🔒 **Privacy.** No internet permission, data stays on the phone and is always encrypted (SQLCipher). Fingerprint or PIN to open (optionally PIN only, and then the key itself refuses a fingerprint), automatic lock, screenshots blocked. Backups, exports and changes to the protection are confirmed with the phone's PIN. Optionally a neutral name and icon. Backups only with a password (Argon2id + AES-256).
- 🗂️ **Matters and attention.** Focus, background and sleeping matters, next steps, stuck ones and ones without a step, sorting out old overdue items.
- 👥 **People.** Your own categories, stances in matters, a team, one-to-one talks, an address book with .vcf and CSV import.
- 📅 **Calendar and time.** Month, week with an hour grid, list; week density with free windows; deadlines, delegations and promises on one strip.
- 📊 **At a glance.** Week, month and year overviews; every number opens the records it counts. Colours always come with a label or a percentage.
- 🧳 **Trip mode and lens.** Marked matters and everything linked to them (people, diary entries, communities) disappear from screens, search, AI and export. Turning trip mode off needs the phone's fingerprint or PIN.
- 📤 **Export and import.** .vcf, .ics, CSV, Markdown, PDF, an Obsidian vault, a packet of matters for a colleague; for any period. By default an encrypted Mandat file; a ZIP with a password for other apps.
- 🔭 **Retrospective.** Directions, past priorities and month-by-month movement from a journal of changes.
- ✍️ **Texts.** Letters, speeches, columns, notes with templates, versions and export to Word and PDF.
- 📓 **Diary, budget, inbox.**
- 🎭 **Demo.** An invented owner with a large base, to try everything without your own data; nothing is kept.
- 🤖 **On-device AI (Gemma), optional:** splits pasted text into matters and delegations, writes a brief and a summary, shortens a speech. Everything stays on the phone; only suggestions.

## Install

Download the `.apk` from [Releases](../../releases/latest). For updates, use [Obtainium](https://obtainium.imranr.dev/). Make a backup before updating.

AI (optional): Options → AI → Download the model (≈2.6 GB) → Choose the model file. The file is checked by its checksum.

## Transparency

- **Permissions:** notifications and restoring reminders after a restart; reading the phone's calendar only if you turn on showing it. The build stops if an internet permission appears.
- **Encryption:** the only things not encrypted are appearance and security settings (theme, PIN only, screenshots, automatic lock, neutral icon), the reminder and phone-calendar switches, a mark that Mandat copied something to the clipboard (without the text), technical AI marks and the model file (none of your data) and the dates that have reminders (so notifications arrive without unlocking; the notification text says nothing). Android also remembers the folder chosen for automatic backups. An open export file only by your choice and after a warning.
- **Build:** the APK is built from this code in GitHub Actions apart from the tests and signed only after the author's approval. Checksums of the dependencies and of Gradle itself are verified; each APK's SHA-256 is published with the release.
- **Limits:** not for classified information, which needs specially protected systems rather than an app on a personal phone. Trip mode does not protect against someone who forces you to open Mandat and confirm turning it off; backups contain everything.
- **How it was made:** written with AI assistance (Claude, Anthropic), directed and tested by the author.

Kotlin · Jetpack Compose · Room · SQLCipher · [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Third-party material: [NOTICE.md](NOTICE.md). GPL-3.0 licence.
