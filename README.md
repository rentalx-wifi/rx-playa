# RX Playa

**Музыка без подписок и без чужих рекомендаций.**

Android-клиент, который играет оттуда, где трек реально есть: YouTube Music,
SoundCloud, Spotify, Яндекс Музыка, VK, локальные файлы и аддоны — в одном
списке, с одним поиском. Где именно играет трек, решает приложение, а не
пользователь.

*An Android music client with multi-source playback. Russian-first readme;
the build and install sections are the ones to copy.*

---

## Возможности

**Источники**
YouTube Music, SoundCloud, Spotify, Яндекс Музыка, VK, локальные файлы и аддоны
в одном поиске и одной очереди. Импорт плейлистов из чужого сервиса, лайки и
плейлисты аккаунта, история прослушиваний.

**RX Wave**
Персональная станция из *ваших* лайков, а не из глобального чарта. Свои треки
служат семенем, радио каждого из них — источником похожего, и дальше работает
фильтр: знакомые артисты вперёд, уже слышанное мимо, чужие slowed / cover /
karaoke / 8D-перепевки отбрасываются, больше двух треков на артиста и никаких
двух подряд.

**Подмена источника**
Если YouTube не отдаёт трек (регион, возраст, удалён), он ищется на остальных
включённых источниках: сначала тот же артист с любой длительностью, затем
совпадение длительности до секунды. Чужой артист с другой длительностью не
подставляется, и замена не происходит молча — приложение говорит, откуда играет.

**Оформление**
Material 3, перенесённый из Pixel Music: палитра, Montserrat, скругления
8/16/24, парящий док, настройки карточками, заголовки разделов в акцентном
цвете. Цвета из обоев (Material You) или свой акцент.

**Звук**
Automix и кроссфейд, bit-perfect и USB DAC, выбор PCM, эквалайзер, Dolby Atmos,
пространственное аудио, нормализация громкости, пропуск тишины, скорость без
изменения тона.

**Ещё**
Текст песен с переводом и подсветкой строки, скачивание и офлайн, Discord Rich
Presence, Listen Together, обложки-канвасы, плеер с очередью и текстом в одном
экране.

## Статус

- **Обход замедления YouTube (zapret) — бета.** Нужен root. Скрипт переписан с
  нуля (раньше `su -c` съедал аргументы, и nfqws не запускался ни разу), но на
  части прошивок он всё ещё не заводится. Это ожидаемо, а не сюрприз.
- **Виджеты — в работе.** Новые переносятся из Pixel Music; до тех пор работают
  старые: квадратный, широкий и пилюля.

## Установка

Готовый APK лежит в [Releases](../../releases): `arm64-v8a`, Android 8.0+
(`minSdk 26`).

Подпись не менялась с первых сборок, поэтому новая версия ставится поверх
предыдущей: настройки, лайки и скачанное остаются на месте.

Пакет: `com.dev.bitchord`.

## Сборка

Нужны JDK 17, Android SDK с `compileSdk 37` и build-tools 37.

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

# debug
./gradlew :app:assembleDevDebug

# release, arm64 only
./gradlew :app:assembleDevRelease \
  -PbitchordAbi=arm64-v8a \
  -Pbitchord.bundleWear=false
```

Для подписанной сборки положите `keystore.properties` рядом с `app/` по образцу
[`keystore.properties.example`](keystore.properties.example) и передайте init-
script, который применит подпись:

```bash
./gradlew :app:assembleDevRelease -PbitchordAbi=arm64-v8a \
  --init-script build/rx-local-signing.init.gradle
```

Ключи, `keystore.properties`, `local.properties` и `build/` в репозиторий не
попадают — они в `.gitignore`.

## Документация

- [`docs/ACCOUNT_INTEGRATIONS.md`](docs/ACCOUNT_INTEGRATIONS.md) — подключение Spotify, SoundCloud и VK
- [`docs/YANDEX_MUSIC.md`](docs/YANDEX_MUSIC.md) — Яндекс Музыка
- [`docs/LYRICS_TRANSLATION.md`](docs/LYRICS_TRANSLATION.md) — перевод текстов

## Благодарности

- [BitChord](https://github.com/kushagrasinghx/BitChord) — основа, из которой
  вырос этот форк.
- [Pixel Music](https://github.com/ianshulyadav/PixelMusic) — оформление,
  которое здесь перенесено: палитра, типографика, формы.
- Montserrat — шрифт под OFL, лицензия лежит рядом с файлом шрифта.

## Лицензия

GPL-3.0 — см. [`LICENSE`](LICENSE).

## Дисклеймер

Неофициальный клиент. Не связан с YouTube, Spotify, SoundCloud, VK или Яндексом;
подписки не нужны, аккаунт используется свой собственный. Проект не хранит
музыку и не распространяет её.
