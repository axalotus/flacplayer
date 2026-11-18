# Zuna — Hi-Res Lossless Audio Player (Android & Desktop)

![Version](https://img.shields.io/badge/Version-v1.1-00B4D8?style=for-the-badge)
![Platform](https://img.shields.io/badge/Platforms-Android%20%7C%20Linux%20%7C%20Windows-06D6A0?style=for-the-badge)
![Engine](https://img.shields.io/badge/Audio-C%2B%2B17%20Oboe%20%7C%20miniaudio-FFB703?style=for-the-badge)
![100% AI Generated](https://img.shields.io/badge/100%25%20AI%20Written-Google%20Antigravity-9B5DE5?style=for-the-badge)

**Zuna** (`zuna-player`) — монохромный аудиофильский монорепозиторий, объединяющий мобильный клиент для **Android** (собственный C++17 / NDK DSP-движок на базе Google Oboe + Jetpack Compose) и десктопный клиент для **Linux / Windows** (`desktop/` на базе Electron + нативного C++17 аддона на `miniaudio` / FFmpeg).

> **Дисклеймер: 100% AI-Generated Code**  
> Весь код в этом репозитории написан нейросетью **Google Antigravity AI** под руководством человека-архитектора в диалоговом режиме: нативные C++17 аудиодвижки, кольцевые буферы `RingBuffer`, 10-полосные каскадные биквадратные эквалайзеры, декодеры форматов, синхронизированная караоке-лирика, OTA-апдейтер, система тем `.zunatheme`, интерфейс на Jetpack Compose и весь десктоп-клиент на Electron.

---

## Zuna Desktop (`desktop/`) — Linux & Windows

Кроссплатформенный десктопный клиент на Electron с GPU-ускоренным интерфейсом, нативным C++17 аудиотрактом и гибкой кастомизацией.

### Возможности Desktop-версии:
- **Нативный C++17 движок (`miniaudio` + FFmpeg) и WebAudio fallback:**
  - Поддержка **FLAC, WAV, MP3, ALAC, AAC/M4A, Ogg Vorbis, Opus, AIFF/AIFC**.
  - **Эксклюзивный захват ЦАП:** `ALSA Direct Exclusive` (с временной приостановкой PipeWire-sink и аппаратным регулятором громкости) на Linux и `WASAPI Exclusive / Shared` на Windows, без программного ресемплинга.
  - **Bit-Transparent тракт и 10-полосный EQ:** при выключенном или плоском эквалайзере звук идёт в обход IIR-фильтров; при включении работают `Preamp`, `Sub-Bass 80 Hz`, 10 полос с живым графиком АЧХ, а также `Gapless`, `Crossfade` и `ReplayGain`.
- **Полный стоп и системная интеграция:**
  - Кнопка полного стопа на мини-плеере и полноэкранной сцене: останавливает поток ЦАП, сбрасывает позицию на `0:00` и выставляет `Stopped` в медиаконтролах.
  - Нативный **MPRIS2 D-Bus сервис** на Linux (`org.mpris.MediaPlayer2.zuna`) для Noctalia/KDE/GNOME (обложка, метаданные, прогресс, перемотка, Play/Pause/Stop/Next/Prev, Loop/Shuffle, громкость).
- **Энергосбережение без влияния на звук:**
  - Режимы **Standard**, **Balanced**, **Автоматически при низком заряде (≤ 20%)** и **Battery Saver** + ручные переключатели («Снижение нагрузки»): отключение тяжёлого blur, уменьшение анимаций, статичный фон вместо видео, отключение визуализатора, ограничение частоты обновления UI и миниатюры обложек.
  - Автоматическая пауза фонового видео при переходе в полноэкранный режим или скрытии окна.
- **Тексты песен и темы `.zunatheme` / `.flactheme`:**
  - Автоопределение кодировок UTF-8 / UTF-16 / Windows-1251 для `.lrc`/`.txt`, поиск в тегах и **LRCLIB**, послоговый караоке-рендеринг и индивидуальный сдвиг тайминга по каждому треку.
  - Экспорт/импорт тем вместе с прикреплёнными фонами в ZIP-пакет **`.zunatheme`** (поддерживается импорт старых **`.flactheme`**).

### Запуск и сборка Desktop:
```bash
cd desktop
npm install            # Установит зависимости и соберёт zuna_native.node через node-gyp
npm start              # Запуск в режиме разработки
npm run dist:linux     # Сборка AppImage / deb / tar.gz для Linux
npm run dist:win       # Сборка Portable / NSIS Setup для Windows (запускать на Windows)
```

---

## Zuna Mobile (`app/`) — Android

### Ключевые возможности Android-версии:
- **Нативный C++17 / NDK Аудиодвижок (Google Oboe):** вывод через `AAudio` (`LowLatency`) / `OpenSL ES` / `Hi-Res Direct` для **FLAC, WAV, MP3, ALAC, AAC/M4A, Ogg Vorbis, Opus, AIFF**, событийный `RingBuffer`, `Gapless`, `Crossfade`, `ReplayGain` и кнопка полного стопа (`STATE_STOPPED`, освобождение аудиопотока и сброс на `0:00`).
- **10-полосный параметрический Biquad Эквалайзер + Bass Boost 80 Hz** с контролем устойчивости полюсов и lock-free снимками коэффициентов.
- **Энергосбережение (Android):** интеграция с системным `PowerManager.isPowerSaveMode` и уровнем батареи `≤ 20%`, профили `Standard` / `Balanced` / `Авто` / `Battery Saver` и ручные переключатели без какого-либо вмешательства в аудиотракт.
- **Синхронизированные тексты и темы:** SYLT / USLT / FLAC LYRICS / `.lrc` / LRCLIB API с караоке-подсветкой, а также совместимость тем `.zunatheme` / `.flactheme` с Desktop.
- **Встроенный OTA-апдейтер** через GitHub Releases API (`axalotus/zuna-player`).

### Сборка релизного APK:
```bash
./gradlew :app:assembleRelease
```
Готовый APK: `app/build/outputs/apk/release/app-release.apk`

---

## Архитектура репозитория

```text
zuna-player/
├── desktop/                         # Десктоп-клиент (Linux & Windows, Electron + C++17)
│   ├── main.js                      # Главный процесс, сканер, кэш обложек, .zunatheme ZIP, LRCLIB
│   ├── preload.js                   # ContextBridge IPC API
│   ├── binding.gyp                  # Сборка нативного N-API аддона zuna_native.node
│   ├── native/                      # C++17 движок (miniaudio, FFmpeg, ALAC, EQ, Linux MPRIS2)
│   ├── package.json                 # Конфигурация Electron и electron-builder
│   ├── assets/                      # Иконка приложения
│   └── renderer/                    # Интерфейс, WebAudio fallback, темизация, визуализатор
└── app/src/main/
    ├── cpp/                         # Нативный C++17 аудиодвижок (NDK + CMake + Oboe)
    └── java/com/zuna/player/
        ├── audio/                   # NativeAudioEngine, DacDetector, EqualizerController, Notification
        ├── data/                    # AudioRepository, LyricsRepository, LRCLIB, OTA, Preferences
        ├── service/                 # AudioPlayerService & Foreground MusicPlaybackService
        └── ui/                      # Экраны и компоненты на Jetpack Compose
```

---

## Лицензия
Проект распространяется свободно в образовательных и энтузиастских целях. Сторонние декодеры (`miniaudio`, `dr_flac`, `dr_wav`, `minimp3`, `FFmpeg`) и `Google Oboe` распространяются под своими оригинальными лицензиями.
