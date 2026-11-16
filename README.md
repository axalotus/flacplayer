# 🎧 FlacPlayer — Hi-Res Lossless Audio Player for Android

![Version](https://img.shields.io/badge/Version-v1.20-00B4D8?style=for-the-badge)
![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-06D6A0?style=for-the-badge)
![Engine](https://img.shields.io/badge/Audio%20Engine-C%2B%2B17%20%2B%20Google%20Oboe-FFB703?style=for-the-badge)
![100% AI Generated](https://img.shields.io/badge/100%25%20AI%20Written-Google%20Antigravity-9B5DE5?style=for-the-badge)

**FlacPlayer** — аудиофильский плеер для Android, спроектированный вокруг собственного **C++17 / NDK DSP-движка** на базе **Google Oboe (AAudio / OpenSL ES)** и современного интерфейса на **Jetpack Compose (Material 3 + AMOLED Pitch Black)**.

> 🤖 **Дисклеймер: 100% AI-Generated Code**  
> **Абсолютно весь код в этом репозитории — на 100% от первой до последней строчки — написан нейросетью (Google Antigravity AI)** под руководством человека-архитектора в диалоговом режиме: включая нативный C++17 аудиодвижок, кольцевой буфер `RingBuffer`, 10-полосный каскадный биквадратный эквалайзер, ресемплер, парсеры заголовков FLAC/WAV/MP3, синхронизированную систему караоке-лирики с прогрессивным блюром, OTA-апдейтер и весь UI на Jetpack Compose.

---

## ✨ Ключевые возможности

### 🔊 Нативный C++17 / NDK Аудиодвижок
- **Google Oboe Backend:** низкоуровневый вывод звука через `AAudio` (PerformanceMode `LowLatency`) с автоматическим откатом на `OpenSL ES` и режимом `Hi-Res Direct`.
- **Прямое декодирование в C++ (32-bit Float PCM):**
  - **FLAC** (`dr_flac`) — поддержка вплоть до **32-bit / 192 kHz Hi-Res Lossless**.
  - **WAV** (`dr_wav`) — честный PCM/IEEE Float декодинг без лишних промежуточных конвертаций.
  - **MP3** (`minimp3_ex`) — потоковое чтение с буфером подкачки 128 КБ (`mp3dec_ex_open_cb`) и по-фреймовой нормализацией Mono/Stereo.
- **Событийный кольцевой буфер (`RingBuffer`):** синхронизация фонового потока декодера и real-time аудиоколбэка через `std::condition_variable` без поллинга CPU и без опустошения буфера на 192 кГц.
- **Gapless, Crossfade & ReplayGain:**
  - Бесшовное переиспользование открытого аппаратного потока Oboe между треками одной частоты дискретизации.
  - Настраиваемый кроссфейд (`0–10 сек`) с по-сэмпловым экспоненциальным сглаживанием громкости без щелчков.
  - Анализ громкости RMS/Peak с мягким лимитированием в реальном времени (`ReplayGain`).

### 🎛️ 10-полосный параметрический Biquad Эквалайзер + Bass Boost
- **Честная DSP-фильтрация в C++ (`EqualizerEngine` + `BiquadFilter`):**
  - 10 полос (`31 Hz`, `62 Hz`, `125 Hz`, `250 Hz`, `500 Hz`, `1 kHz`, `2 kHz`, `4 kHz`, `8 kHz`, `16 kHz`) + отдельный фильтр `Low Shelf` на `80 Hz` для **Bass Boost**.
  - Проверка устойчивости полюсов фильтра по критерию Джури (защита от самовозбуждения IIR-фильтра и `NaN`/`Inf`) и защита от денормализованных чисел.
  - Бесшовная передача снимков коэффициентов (`EqCoeffsSnapshot`) из UI-потока в аудиопоток без пропуска буферов и без щелчков при движении ползунков.

### 🎤 Синхронизированные тексты песен (LRC & LRCLIB)
- Автоматический поиск синхронизированной (`.lrc`) и обычной лирики из трёх источников:
  1. Вшитые в аудиофайл теги (`SYLT` / `USLT` в ID3v2 и `LYRICS` во FLAC Vorbis Comments).
  2. Локальные `.lrc` / `.txt` файлы из папки с музыкой или выделенной папки текстов.
  3. Онлайн-база **LRCLIB API**.
- **Apple Music-style караоке-режим:** автоцентрирование активной строки, плавный прогрессивный `Gaussian Blur` для предыдущих и следующих строчек в зависимости от дистанции до текущей строки, мягкое растворение краёв (`BlendMode.DstIn`) и мгновенная перемотка трека по тапу на любую строчку.

### ⚡ Быстрая медиатека и интерфейс
- **Молниеносное сканирование SAF (`DocumentFile` / `DocumentsContract`):** пакетные запросы к `ContentResolver`, прямое чтение заголовков `STREAMINFO` (FLAC) и `fmt ` (WAV) через `Os.pread` и инкрементальный кэш метаданных.
- **Мониторинг аудио-тракта (`Audio Signal Chain` & `DacDetector`):** отображение всей цепочки прохождения сигнала — от формата исходника и разрядности декодера до активного USB ЦАП / Bluetooth-кодека и частоты дискретизации выходного каскада.
- **Темы оформления:** Светлая, Тёмная и настоящая **AMOLED Pitch Black** (`#000000`).
- **Встроенный OTA-апдейтер:** проверка новых релизов напрямую через GitHub Releases API со скачиванием и установкой APK в один тап.

---

## 🏗️ Архитектура проекта

```text
flacplayer/
└── app/src/main/
    ├── cpp/                         # Нативный C++17 аудиодвижок (NDK + CMake)
    │   ├── AudioEngine.cpp/.h       # Управление потоком Oboe, ReplayGain, Volume smoothing
    │   ├── AudioDecoder.cpp/.h      # Декодеры FLAC, WAV, MP3 + линейный стерео-ресемплер
    │   ├── EqualizerEngine.cpp/.h   # 10-полосный EQ + Bass Boost (Double-snapshot lock-free DSP)
    │   ├── BiquadFilter.cpp/.h      # Каскадные IIR-фильтры Peaking / Low-Shelf / High-Shelf
    │   ├── RingBuffer.h             # Потокобезопасный кольцевой буфер сэмплов
    │   └── native-lib.cpp           # JNI-мост между Kotlin и C++
    └── java/com/antigravity/audioplayer/
        ├── audio/                   # NativeAudioEngine, DacDetector, EqualizerController, SleepTimer
        ├── data/                    # AudioRepository, LyricsRepository, LRCLIB, OTA, Preferences
        ├── service/                 # AudioPlayerService & Foreground MusicPlaybackService
        └── ui/                      # Экраны и компоненты на Jetpack Compose (Material 3)
```

---

## 🛠️ Сборка из исходников

### Требования
- **JDK 17+**
- **Android SDK** (`compileSdk = 36`, `minSdk = 24`)
- **Android NDK + CMake 3.22.1+**

### Сборка релизного APK
```bash
./gradlew :app:assembleRelease
```
Готовый APK будет находиться по пути:
`app/build/outputs/apk/release/app-release.apk`

---

## 📄 Лицензия
Проект распространяется свободно в образовательных и энтузиастских целях. Сторонние однофайловые декодеры (`dr_flac`, `dr_wav`, `minimp3`) и `Google Oboe` распространяются под своими оригинальными лицензиями (Public Domain / Unlicense / Apache 2.0).
