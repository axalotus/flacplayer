# Zuna

Локальный аудиоплеер для **Android**, **Linux** и **Windows** со своим аудиодвижком, эксклюзивным выводом на ЦАП и кастомизируемым интерфейсом.

Готовые сборки (`.apk`, `.exe`, `AppImage` / `.deb`): [Releases](https://github.com/axalotus/zuna-player/releases)

## Аудиотракт и форматы

### Android (`app/`)
- Вывод через **Google Oboe**: `AAudio` (Low Latency), `OpenSL ES` и обход системного микшера (`Hi-Res Direct`, где поддерживает железо).
- Декодеры в C++: `dr_flac`, `dr_wav`, `minimp3`, встроенный декодер `ALAC` и NDK `MediaExtractor` / `MediaCodec` для остальных форматов.
- Форматы: `FLAC`, `WAV`, `MP3`, `ALAC`, `AAC / M4A`, `Ogg Vorbis`, `Opus`, `AIFF / AIFC`.

### Desktop (`desktop/`)
- Свой нативный N-API аддон `zuna_native.node` на **C++17 + `miniaudio`**:
  - **Linux:** `PipeWire` / `PulseAudio` в обычном режиме, `ALSA Direct Exclusive` (с приостановкой sink в PipeWire и синхронизацией системного регулятора громкости) в эксклюзивном режиме; для `AAC/M4A`, `Ogg Vorbis`, `Opus` и `AIFF` подключён `FFmpeg` (`libavformat` / `libavcodec` / `libswresample`).
  - **Windows:** `WASAPI` (`Shared` и строгий `Exclusive` без тихого отката в микшер, с перебором `S32 -> S24 -> F32 -> S16`); то, что не декодируется в нативе, автоматически падает на `WebAudio` внутри Electron.

### Общее для обеих платформ
- **10-полосный параметрический Biquad-эквалайзер** + `Preamp` + `Sub-Bass 80 Hz`.
- **Gapless**, **Crossfade**, **ReplayGain**.

---

## Интерфейс, тексты, энергосбережение

- **Тексты песен:** читает `.lrc` / `.txt`, встроенные теги (`SYLT`, `USLT`, `FLAC LYRICS`) и подтягивает тайминги из **LRCLIB**. Тайминг можно сдвигать вручную отдельно для каждого трека.
- **Кастомизация и перенос тем:** акценты, цвет/прозрачность/блюр панелей, скругления, шрифты, отдельные фоны для главного экрана и полноэкранного плеера (сплошной цвет, размытая обложка, фото, GIF или видео). Вся тема вместе с медиафайлами пакуется в один файл `.zunatheme`. Темы совместимы между Android и Desktop.
- **Энергосбережение:** профили **Standard**, **Balanced**, **Автоматически при низком заряде** (`PowerManager` на Android / батарея <= 20% без зарядки) и **Battery Saver** + ручные тумблеры на каждый эффект (блюр, анимации, видеофон, визуализатор, частота обновления UI, миниатюры обложек). На качество звука, частоту дискретизации, Gapless и эксклюзивный режим это не влияет.

---

## Сборка из исходников

### Android
Требуется Android SDK + NDK и JDK 17:
```bash
./gradlew :app:assembleRelease
```
Готовый APK: `app/build/outputs/apk/release/app-release.apk` (`applicationId`: `com.zuna.player`).

### Desktop (Linux)
Нужны Node.js, Python 3, компилятор C++ и dev-библиотеки `FFmpeg` / `glib`:
```bash
# Пример зависимостей для Arch / CachyOS
sudo pacman -S ffmpeg glib2 pkgconf base-devel

# Для Debian / Ubuntu
sudo apt install pkg-config libglib2.0-dev libavformat-dev libavcodec-dev libavutil-dev libswresample-dev build-essential

cd desktop
npm install
npm start            # запуск в dev-режиме
npm run dist:linux   # сборка AppImage / deb / tar.gz
```

### Desktop (Windows)
Нужны Node.js x64, Python 3 и Visual Studio Build Tools (компонент *Desktop development with C++*):
```powershell
cd desktop
npm install
npm run dist:win     # соберёт Portable.exe и Setup.exe в desktop/dist/
```

---

## Структура проекта

```text
zuna-player/
├── app/src/main/
│   ├── cpp/                    # C++17 аудиодвижок для Android (Oboe, декодеры, Biquad EQ)
│   └── java/com/zuna/player/   # Jetpack Compose UI, AudioPlayerService, база треков, LRCLIB, OTA
├── desktop/
│   ├── native/                 # C++17 N-API аддон (miniaudio, WASAPI/ALSA, FFmpeg, MPRIS2)
│   ├── renderer/               # Интерфейс, WebAudio fallback, темизация, визуализатор
│   ├── main.js                 # Electron main, сканер, кэш обложек, .zunatheme, LRCLIB
│   ├── preload.js              # IPC bridge
│   └── binding.gyp             # Конфиг сборки zuna_native.node
└── .github/workflows/          # Автосборка релизов под Android, Linux и Windows
```
