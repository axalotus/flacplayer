# FlacPlayer — Hi-Res Lossless Audio Player (Android & Desktop)

![Version](https://img.shields.io/badge/Version-v1.0-00B4D8?style=for-the-badge)
![Platform](https://img.shields.io/badge/Platforms-Android%20%7C%20Linux%20%7C%20Windows-06D6A0?style=for-the-badge)
![Engine](https://img.shields.io/badge/Audio-C%2B%2B17%20Oboe%20%7C%20Electron%20WebAudio-FFB703?style=for-the-badge)
![100% AI Generated](https://img.shields.io/badge/100%25%20AI%20Written-Google%20Antigravity-9B5DE5?style=for-the-badge)

**FlacPlayer** — аудиофильский монорепозиторий, объединяющий мобильный клиент для **Android** (собственный C++17 / NDK DSP-движок на базе Google Oboe + Jetpack Compose) и десктопный клиент для **Linux / Windows** (`desktop/` на базе Electron + bit-transparent WebAudio-тракт с настраиваемым интерфейсом и `.flactheme` темами).

> **Дисклеймер: 100% AI-Generated Code**  
> **Абсолютно весь код в этом репозитории написан нейросетью (Google Antigravity AI)** под руководством человека-архитектора в диалоговом режиме: нативный C++17 аудиодвижок, кольцевой буфер `RingBuffer`, 10-полосный каскадный биквадратный эквалайзер, ресемплер, парсеры заголовков FLAC/WAV/MP3, синхронизированная система караоке-лирики, OTA-апдейтер, интерфейс на Jetpack Compose и весь десктоп-клиент на Electron.

---

## FlacPlayer Desktop (`desktop/`) — Linux & Windows

Кроссплатформенный десктопный клиент на Electron с GPU-ускоренным интерфейсом, гибкой кастомизацией и чистым аудиовыводом.

### Возможности Desktop-версии:
- **Bit-Transparent аудио-тракт и 10-полосный EQ:** при выключенном или плоском эквалайзере поток идёт напрямую (`sourceNode -> envelopeNode`) без фазовых искажений биквадратных фильтров; при включении активируются `Preamp`, `Sub-Bass 80 Hz` и 10 полос с кривой АЧХ в реальном времени. Поддерживаются выбор устройства вывода (ЦАП), Crossfade и ReplayGain.
- **Синхронизированные тексты песен (`.lrc` / теги / LRCLIB):**
  - Автоопределение кодировок UTF-8 / UTF-16 / Windows-1251 для локальных `.lrc`/`.txt`.
  - Плавный `requestAnimationFrame` скролл с дистанционным блюром строк.
  - Индивидуальная задержка текста (`-0.5s` / `-0.1s` / `+0.1s` / `+0.5s`) с сохранением отдельно для каждого трека.
- **Система тем и экспорт в `.flactheme`:**
  - Полная настройка акцентного цвета, цвета/яркости/прозрачности/блюра панелей, скруглений, рамок, шрифта и стиля визуализатора.
  - Раздельные фоны для главного окна и полноэкранного плеера (чёрный, размытая обложка или собственное фото/GIF/видео) с отдельными режимами Ч/Б или цветного оригинала, размытия и затемнения.
  - Упаковка всей темы вместе с прикреплёнными фонами в один ZIP-файл **`.flactheme`** (экспорт, импорт кнопкой и drag-and-drop файла прямо в окно плеера).
- **Быстрый интерфейс и системная интеграция:**
  - Кэшируемые `220px` миниатюры обложек и изоляция GPU-слоёв для плавного скролла больших коллекций.
  - Компактная левая панель-рейл, сворачиваемая/масштабируемая правая панель (Текст / Очередь), выезжающий полноэкранный плеер и интеграция MPRIS / MediaSession (`MediaMetadata` + обложка + управление с клавиатуры и системных виджетов).

### Запуск и сборка Desktop:
```bash
cd desktop
npm install
npm start              # Запуск в режиме разработки
npm run dist:linux     # Сборка AppImage для Linux
npm run dist:win       # Сборка portable / NSIS инсталлятора для Windows
```

---

## FlacPlayer Mobile (`app/`) — Android

### Ключевые возможности Android-версии:
- **Нативный C++17 / NDK Аудиодвижок (Google Oboe):** вывод через `AAudio` (`LowLatency`) / `OpenSL ES` / `Hi-Res Direct`, прямое декодирование `dr_flac` (вплоть до 32-bit / 192 kHz), `dr_wav`, `minimp3_ex`, событийный `RingBuffer`, Gapless, Crossfade и ReplayGain.
- **10-полосный параметрический Biquad Эквалайзер + Bass Boost 80 Hz** с проверкой устойчивости полюсов и lock-free снимками коэффициентов.
- **Синхронизированные тексты песен** (SYLT / USLT / FLAC LYRICS / локальные `.lrc` / LRCLIB API) с караоке-режимом и прогрессивным блюром.
- **Встроенный OTA-апдейтер** через GitHub Releases API.

### Сборка релизного APK:
```bash
./gradlew :app:assembleRelease
```
Готовый APK: `app/build/outputs/apk/release/app-release.apk`

---

## Архитектура репозитория

```text
flacplayer/
├── desktop/                         # Десктоп-клиент (Linux & Windows, Electron)
│   ├── main.js                      # Главный процесс, сканер медиатеки, кэш превью, .flactheme ZIP, LRCLIB
│   ├── preload.js                   # ContextBridge IPC API
│   ├── package.json                 # Конфигурация Electron и electron-builder
│   ├── assets/                      # Иконка приложения
│   └── renderer/                    # WebAudio DSP-тракт, интерфейс, темизация, визуализатор
└── app/src/main/
    ├── cpp/                         # Нативный C++17 аудиодвижок (NDK + CMake + Oboe)
    └── java/com/antigravity/audioplayer/
        ├── audio/                   # NativeAudioEngine, DacDetector, EqualizerController
        ├── data/                    # AudioRepository, LyricsRepository, LRCLIB, OTA, Preferences
        ├── service/                 # AudioPlayerService & Foreground MusicPlaybackService
        └── ui/                      # Экраны и компоненты на Jetpack Compose
```

---

## Лицензия
Проект распространяется свободно в образовательных и энтузиастских целях. Сторонние декодеры (`dr_flac`, `dr_wav`, `minimp3`) и `Google Oboe` распространяются под своими оригинальными лицензиями (Public Domain / Unlicense / Apache 2.0).
