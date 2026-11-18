const { app, BrowserWindow, ipcMain, dialog, globalShortcut, nativeImage } = require("electron");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const https = require("https");
const zlib = require("zlib");
const { pathToFileURL } = require("url");

app.commandLine.appendSwitch("enable-gpu-rasterization");
app.commandLine.appendSwitch("enable-zero-copy");
app.commandLine.appendSwitch("ignore-gpu-blocklist");

const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
}

let mainWindow = null;

const AUDIO_EXTENSIONS = new Set([
  ".flac",
  ".wav",
  ".mp3",
  ".ogg",
  ".oga",
  ".opus",
  ".m4a",
  ".mp4",
  ".aac",
  ".alac",
  ".aiff",
  ".aif",
  ".aifc",
  ".caf",
]);
const lyricsMemoryCache = new Map();

function getUserDataDir() {
  const userDataRoot = app.getPath("userData");
  const dir = path.join(userDataRoot, "zuna-state");
  const legacyDir = path.join(userDataRoot, "flacplayer-state");
  if (!fs.existsSync(dir)) {
    if (fs.existsSync(legacyDir)) {
      try {
        fs.cpSync(legacyDir, dir, { recursive: true });
      } catch (_) {}
    }
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  }
  return dir;
}

function getCoversCacheDir() {
  const dir = path.join(getUserDataDir(), "covers");
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function getThumbsCacheDir() {
  const dir = path.join(getUserDataDir(), "thumbs");
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function getThemesAssetsDir() {
  const dir = path.join(getUserDataDir(), "themes-assets");
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function getOnlineCoversCacheDir() {
  const dir = path.join(getUserDataDir(), "online-covers");
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/* Generate compact 220px JPEG thumbnails for buttery-smooth scrolling */
function ensureThumbForCover(coverPath) {
  if (!coverPath || !fs.existsSync(coverPath)) return null;
  try {
    const hash = crypto.createHash("sha1").update(coverPath).digest("hex");
    const thumbFile = path.join(getThumbsCacheDir(), `${hash}.jpg`);
    if (fs.existsSync(thumbFile)) return thumbFile;
    const img = nativeImage.createFromPath(coverPath);
    if (img.isEmpty()) return coverPath;
    const size = img.getSize();
    const resized =
      size.width > 240 || size.height > 240
        ? img.resize({ width: 220, quality: "good" })
        : img;
    fs.writeFileSync(thumbFile, resized.toJPEG(82));
    return thumbFile;
  } catch (_) {
    return coverPath;
  }
}

function getStateFilePath() {
  return path.join(getUserDataDir(), "library-state.json");
}

const DEFAULT_STATE = {
  folders: [],
  tracks: [],
  favoriteIds: [],
  recentlyPlayedIds: [],
  lyricsOffsets: {},
  playlists: [],
  lastPlayback: {
    trackId: null,
    positionSec: 0,
    queueIds: [],
  },
  eqConfig: {
    enabled: false,
    preampDb: 0,
    bassBoost: 0,
    bandGains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    presetName: "Flat",
  },
  settings: {
    crossfadeSeconds: 0,
    replayGainEnabled: false,
    gaplessEnabled: true,
    compactMode: false,
    showTrackNumbers: true,
    showQualityBadges: false,
    sidebarCompact: false,
    fontFamily: "jakarta", // "jakarta" | "manrope" | "inter" | "mono" | "system"
    visualizerEnabled: true,
    visualizerStyle: "bars", // "bars" | "wave" | "mirror"
    accentColor: "#ffffff",
    panelColor: "#0a0a0a",
    panelBrightness: 100,
    panelOpacity: 88,
    cardOpacity: 86,
    panelBlurPx: 14,
    borderRadiusPx: 12,
    borderOpacity: 10,
    lyricsBlurIntensity: 100,
    appBgMode: "none", // "none" | "cover" | "custom"
    appBgColorMode: "mono", // "mono" | "color"
    appBgSolidColor: "#000000",
    appBgCustomPath: "",
    appBgIsVideo: false,
    appBgBlurPx: 48,
    appBgDimPercent: 68,
    fsBgMode: "cover", // "none" | "cover" | "custom"
    fsBgColorMode: "mono", // "mono" | "color"
    fsBgSolidColor: "#000000",
    fsBgCustomPath: "",
    fsBgIsVideo: false,
    fsBgBlurPx: 52,
    fsBgDimPercent: 64,
    volume: 0.85,
    outputDeviceId: "default",
    exclusiveMode: false,
    onlineLyricsEnabled: true,
    onlineCoversEnabled: true,
    hiddenTrackIds: [],
    hideDuplicates: false,
    forceVisibleDuplicateIds: [],
    rightDockWidth: 350,
    rightDockOpen: true,
    powerSaverMode: "standard", // "standard" | "balanced" | "auto" | "battery_saver"
    powerDisableBlur: false,
    powerReduceAnimations: false,
    powerDisableDynamicBg: false,
    powerDisableVisualizer: false,
    powerLimitUiRefresh: false,
    powerUseThumbCovers: false,
  },
};

let nativeEngine = null;
function loadNativeAddon() {
  if (nativeEngine) return nativeEngine;
  const candidates = [
    path.join(__dirname, "build", "Release", "zuna_native.node"),
    process.resourcesPath
      ? path.join(process.resourcesPath, "app.asar.unpacked", "build", "Release", "zuna_native.node")
      : "",
    path.join(__dirname, "build", "Release", "flacplayer_native.node"),
    process.resourcesPath
      ? path.join(process.resourcesPath, "app.asar.unpacked", "build", "Release", "flacplayer_native.node")
      : "",
  ].filter(Boolean);
  for (const p of candidates) {
    try {
      if (fs.existsSync(p)) {
        nativeEngine = require(p);
        break;
      }
    } catch (err) {
      console.error("Failed to load native engine from", p, err);
    }
  }
  return nativeEngine;
}

let appState = loadPersistedState();

function loadPersistedState() {
  try {
    const file = getStateFilePath();
    if (fs.existsSync(file)) {
      const parsed = JSON.parse(fs.readFileSync(file, "utf-8"));
      return {
        ...DEFAULT_STATE,
        ...parsed,
        lastPlayback: { ...DEFAULT_STATE.lastPlayback, ...(parsed.lastPlayback || {}) },
        lyricsOffsets: { ...(parsed.lyricsOffsets || {}) },
        eqConfig: { ...DEFAULT_STATE.eqConfig, ...(parsed.eqConfig || {}) },
        settings: { ...DEFAULT_STATE.settings, ...(parsed.settings || {}) },
      };
    }
  } catch (e) {
    console.error("Failed to load state:", e);
  }
  return structuredClone(DEFAULT_STATE);
}

let saveStateTimer = null;
let saveStateDirty = false;
let saveStateInFlight = false;

async function writePersistedStateAsync() {
  if (saveStateInFlight || !saveStateDirty) return;
  saveStateInFlight = true;
  try {
    while (saveStateDirty) {
      saveStateDirty = false;
      await fs.promises.writeFile(getStateFilePath(), JSON.stringify(appState), "utf-8");
    }
  } catch (e) {
    console.error("Failed to save state:", e);
  } finally {
    saveStateInFlight = false;
  }
}

function savePersistedState(immediate = false) {
  saveStateDirty = true;
  if (saveStateTimer) {
    clearTimeout(saveStateTimer);
    saveStateTimer = null;
  }
  if (immediate) {
    flushPersistedStateSync();
    return;
  }
  saveStateTimer = setTimeout(() => {
    saveStateTimer = null;
    writePersistedStateAsync();
  }, 220);
}

function flushPersistedStateSync() {
  if (saveStateTimer) {
    clearTimeout(saveStateTimer);
    saveStateTimer = null;
  }
  if (!saveStateDirty) return;
  saveStateDirty = false;
  try {
    fs.writeFileSync(getStateFilePath(), JSON.stringify(appState), "utf-8");
  } catch (e) {
    console.error("Failed to save state:", e);
  }
}

/* Smart encoding detector: UTF-8 (with BOM / strict check) -> Windows-1251 (CP1251) fallback */
function decodeTextBufferSmart(buf) {
  if (!buf || buf.length === 0) return "";
  // UTF-16 LE BOM
  if (buf.length >= 2 && buf[0] === 0xff && buf[1] === 0xfe) {
    return new TextDecoder("utf-16le").decode(buf.subarray(2));
  }
  // UTF-16 BE BOM
  if (buf.length >= 2 && buf[0] === 0xfe && buf[1] === 0xff) {
    return new TextDecoder("utf-16be").decode(buf.subarray(2));
  }
  // UTF-8 BOM
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    return new TextDecoder("utf-8").decode(buf.subarray(3));
  }
  try {
    const utf8 = new TextDecoder("utf-8", { fatal: true }).decode(buf);
    if (!utf8.includes("\uFFFD")) {
      return utf8;
    }
  } catch (_) {
    // Not valid UTF-8 -> decode as Cyrillic Windows-1251
    try {
      return new TextDecoder("windows-1251").decode(buf);
    } catch (_) {}
  }
  return new TextDecoder("utf-8").decode(buf);
}

function readTextFileSmart(filePath) {
  const buf = fs.readFileSync(filePath);
  return decodeTextBufferSmart(buf);
}

/* Repairs common corrupted replacement chars inside Russian words (e.g. "Искуплся" -> "Искупался") */
function sanitizeCorruptedLyricsString(str) {
  if (!str) return "";
  return str
    .replace(/Искуп\uFFFD+лся/gi, "Искупался")
    .replace(/\uFFFD+/g, "");
}

function walkAudioFiles(dirPath, outList = []) {
  let entries;
  try {
    entries = fs.readdirSync(dirPath, { withFileTypes: true });
  } catch (_) {
    return outList;
  }
  for (const entry of entries) {
    const fullPath = path.join(dirPath, entry.name);
    if (entry.isDirectory()) {
      walkAudioFiles(fullPath, outList);
    } else if (entry.isFile()) {
      const ext = path.extname(entry.name).toLowerCase();
      if (AUDIO_EXTENSIONS.has(ext)) {
        outList.push(fullPath);
      }
    }
  }
  return outList;
}

function findFolderCover(dirPath) {
  try {
    const files = fs.readdirSync(dirPath);
    const wanted = new Set(["cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "album.jpg"]);
    for (const f of files) {
      if (wanted.has(f.toLowerCase())) {
        return path.join(dirPath, f);
      }
    }
  } catch (_) {}
  return null;
}

async function scanAllFolders() {
  const mm = await import("music-metadata");
  const seen = new Set();
  const tracks = [];
  const coversDir = getCoversCacheDir();
  const existingByPath = new Map();
  for (const prev of appState.tracks || []) {
    if (prev && prev.path) existingByPath.set(prev.path, prev);
  }

  for (const folder of appState.folders) {
    const files = walkAudioFiles(folder);
    for (const filePath of files) {
      if (seen.has(filePath)) continue;
      seen.add(filePath);

      let mtimeMs = 0;
      let dateAdded = 0;
      try {
        const st = fs.statSync(filePath);
        mtimeMs = Math.round(st.mtimeMs || 0);
        dateAdded = Math.round(st.mtimeMs || st.ctimeMs || Date.now());
      } catch (_) {}

      const cached = existingByPath.get(filePath);
      if (
        cached &&
        mtimeMs > 0 &&
        cached.mtimeMs === mtimeMs &&
        (!cached.coverPath || fs.existsSync(cached.coverPath))
      ) {
        const thumbPath = cached.coverPath
          ? cached.thumbPath && fs.existsSync(cached.thumbPath)
            ? cached.thumbPath
            : ensureThumbForCover(cached.coverPath)
          : null;
        tracks.push({ ...cached, thumbPath });
        continue;
      }

      const ext = path.extname(filePath).slice(1).toUpperCase();
      const fallbackTitle = path.basename(filePath, path.extname(filePath));
      let title = fallbackTitle;
      let artist = "Unknown Artist";
      let album = "Unknown Album";
      let trackNumber = 0;
      let durationMs = 0;
      let sampleRateHz = 44100;
      let bitDepth = 16;
      let bitrateKbps = 0;
      let channels = 2;
      let coverPath = null;
      let detectedFormat = ext === "AIF" || ext === "AIFC" ? "AIFF" : ext === "OGA" ? "OGG" : ext;

      try {
        const meta = await mm.parseFile(filePath, { duration: true, skipCovers: false });
        if (meta.common.title && meta.common.title.trim()) title = meta.common.title.trim();
        if (meta.common.artist && meta.common.artist.trim()) artist = meta.common.artist.trim();
        if (meta.common.album && meta.common.album.trim()) album = meta.common.album.trim();
        if (meta.common.track && meta.common.track.no) trackNumber = meta.common.track.no;

        if (meta.format.duration) durationMs = Math.round(meta.format.duration * 1000);
        if (meta.format.sampleRate) sampleRateHz = meta.format.sampleRate;
        if (meta.format.bitsPerSample) bitDepth = meta.format.bitsPerSample;
        if (meta.format.bitrate) bitrateKbps = Math.round(meta.format.bitrate / 1000);
        if (meta.format.numberOfChannels) channels = meta.format.numberOfChannels;
        const codecLow = String(meta.format.codec || "").toLowerCase();
        if (codecLow.includes("alac")) detectedFormat = "ALAC";
        else if (codecLow.includes("opus")) detectedFormat = "OPUS";
        else if (codecLow.includes("vorbis")) detectedFormat = "OGG";
        else if (codecLow.includes("aac")) detectedFormat = "AAC";
        else if (codecLow.includes("flac")) detectedFormat = "FLAC";
        else if (ext === "AIFF" || ext === "AIF" || ext === "AIFC") detectedFormat = "AIFF";

        const pic = meta.common.picture && meta.common.picture[0];
        if (pic && pic.data && pic.data.length > 0) {
          const hash = crypto
            .createHash("sha1")
            .update(`${album}__${artist}__${pic.data.length}`)
            .digest("hex");
          const extImg = pic.format && pic.format.includes("png") ? ".png" : ".jpg";
          const cachedFile = path.join(coversDir, `${hash}${extImg}`);
          if (!fs.existsSync(cachedFile)) {
            fs.writeFileSync(cachedFile, pic.data);
          }
          coverPath = cachedFile;
        }
      } catch (_) {}

      if (!coverPath) {
        coverPath = findFolderCover(path.dirname(filePath));
      }
      const thumbPath = coverPath ? ensureThumbForCover(coverPath) : null;

      tracks.push({
        id: filePath,
        path: filePath,
        title,
        artist,
        album,
        trackNumber,
        durationMs,
        format: detectedFormat,
        sampleRateHz,
        bitDepth,
        bitrateKbps,
        channels,
        coverPath,
        thumbPath,
        dateAdded,
        mtimeMs,
      });
    }
  }

  tracks.sort((a, b) => {
    const ar = a.artist.localeCompare(b.artist);
    if (ar !== 0) return ar;
    const al = a.album.localeCompare(b.album);
    if (al !== 0) return al;
    const ta = a.trackNumber > 0 ? a.trackNumber : 9999;
    const tb = b.trackNumber > 0 ? b.trackNumber : 9999;
    if (ta !== tb) return ta - tb;
    return a.title.localeCompare(b.title);
  });

  appState.tracks = tracks;
  savePersistedState();
  return appState;
}

function parseLrcTimestamp(tag) {
  const match = /^(\d{1,3}):(\d{1,2})(?:[.,:](\d{1,3}))?$/.exec(tag.trim());
  if (!match) return null;
  const min = parseInt(match[1], 10);
  const sec = parseInt(match[2], 10);
  let ms = 0;
  if (match[3]) {
    const frac = match[3];
    if (frac.length === 1) ms = parseInt(frac, 10) * 100;
    else if (frac.length === 2) ms = parseInt(frac, 10) * 10;
    else ms = parseInt(frac.slice(0, 3), 10);
  }
  return min * 60000 + sec * 1000 + ms;
}

function parseEnhancedLineSegments(rawBody, fallbackStartMs) {
  const wordRe = /<(\d{1,3}:\d{1,2}(?:[.,:]\d{1,3})?)>/g;
  const segments = [];
  let plainOut = "";
  let trailingEndMs = null;
  let lastCursor = 0;
  let curTs = fallbackStartMs;
  let match;
  let foundTag = false;

  while ((match = wordRe.exec(rawBody)) !== null) {
    foundTag = true;
    const textSlice = rawBody.slice(lastCursor, match.index);
    if (textSlice.length > 0) {
      plainOut += textSlice;
      if (textSlice.trim().length > 0) {
        segments.push({ text: textSlice, timestampMs: curTs, endMs: 0 });
      } else if (segments.length > 0) {
        segments[segments.length - 1].text += textSlice;
      }
    }
    const parsed = parseLrcTimestamp(match[1]);
    if (parsed !== null) curTs = parsed;
    lastCursor = wordRe.lastIndex;
  }

  if (!foundTag) {
    return { cleanText: rawBody.trim(), words: [], trailingEndMs: null };
  }

  const tailSlice = rawBody.slice(lastCursor);
  if (tailSlice.length > 0) {
    plainOut += tailSlice;
    if (tailSlice.trim().length > 0) {
      segments.push({ text: tailSlice, timestampMs: curTs, endMs: 0 });
    } else if (segments.length > 0) {
      segments[segments.length - 1].text += tailSlice;
    }
  } else if (segments.length > 0 && curTs > segments[segments.length - 1].timestampMs) {
    trailingEndMs = curTs;
  }

  return {
    cleanText: plainOut.trim(),
    words: segments,
    trailingEndMs,
  };
}

function parseLyricsRawText(rawInput, sourceLabel) {
  const raw = sanitizeCorruptedLyricsString(rawInput);
  if (!raw || !raw.trim()) return null;
  const syncedLines = [];
  const plainLines = [];
  let offsetMs = 0;

  for (const rawLine of raw.split(/\r?\n/)) {
    let rest = rawLine.replace(/^\uFEFF/, "").trim();
    if (!rest) continue;

    const offsetMatch = /^\[offset:\s*([+-]?\d+)\]$/i.exec(rest);
    if (offsetMatch) {
      offsetMs = parseInt(offsetMatch[1], 10) || 0;
      continue;
    }

    const timestamps = [];
    while (rest.startsWith("[")) {
      const closeIdx = rest.indexOf("]");
      if (closeIdx === -1) break;
      const tagContent = rest.slice(1, closeIdx);
      const ms = parseLrcTimestamp(tagContent);
      if (ms !== null) {
        timestamps.push(ms);
        rest = rest.slice(closeIdx + 1);
      } else {
        rest = rest.slice(closeIdx + 1);
        break;
      }
    }

    const rawBody = rest.trimStart();
    if (timestamps.length > 0) {
      const firstTs = timestamps[0];
      const parsed = parseEnhancedLineSegments(rawBody, firstTs);
      const text = parsed.cleanText || "· · ·";
      for (const ts of timestamps) {
        const delta = ts - firstTs;
        const shiftedWords = parsed.words.map((w) => ({
          text: w.text,
          timestampMs: Math.max(0, w.timestampMs + delta + offsetMs),
          endMs: 0,
        }));
        syncedLines.push({
          timestampMs: Math.max(0, ts + offsetMs),
          text,
          words: shiftedWords,
          _trailingEndMs: parsed.trailingEndMs !== null ? Math.max(0, parsed.trailingEndMs + delta + offsetMs) : null,
        });
      }
    } else if (rawBody.trim() && !rawLine.trim().startsWith("[")) {
      plainLines.push(rawBody.replace(/<\d{1,3}:\d{1,2}(?:[.,:]\d{1,3})?>/g, "").trim());
    }
  }

  if (syncedLines.length > 0) {
    syncedLines.sort((a, b) => a.timestampMs - b.timestampMs);
    let hasWordSync = false;
    for (let i = 0; i < syncedLines.length; i++) {
      const line = syncedLines[i];
      const nextLine = i + 1 < syncedLines.length ? syncedLines[i + 1] : null;
      if (line.words && line.words.length > 0) {
        hasWordSync = true;
        for (let k = 0; k < line.words.length; k++) {
          const w = line.words[k];
          if (k + 1 < line.words.length) {
            w.endMs = Math.max(w.timestampMs + 60, line.words[k + 1].timestampMs);
          } else if (line._trailingEndMs !== null && line._trailingEndMs > w.timestampMs) {
            w.endMs = line._trailingEndMs;
          } else if (nextLine && nextLine.timestampMs > w.timestampMs) {
            w.endMs = Math.min(nextLine.timestampMs, w.timestampMs + 6000);
          } else {
            w.endMs = w.timestampMs + Math.max(500, w.text.trim().length * 160);
          }
        }
      }
      delete line._trailingEndMs;
    }
    return {
      isSynced: true,
      hasWordSync,
      isInstrumental: false,
      source: sourceLabel,
      lines: syncedLines,
    };
  }

  if (plainLines.length > 0) {
    return {
      isSynced: false,
      hasWordSync: false,
      isInstrumental: false,
      source: sourceLabel,
      lines: plainLines.map((t) => ({ timestampMs: 0, text: t, words: [] })),
    };
  }

  return null;
}

function fetchJsonHttps(url) {
  return new Promise((resolve) => {
    const req = https.get(
      url,
      {
        headers: {
          "User-Agent": "Zuna-Desktop/1.1.0 (https://github.com/debilxxx/Zuna-player)",
          Accept: "application/json",
        },
        timeout: 7000,
      },
      (res) => {
        if (res.statusCode !== 200) {
          resolve(null);
          return;
        }
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          try {
            const body = Buffer.concat(chunks).toString("utf-8");
            resolve(JSON.parse(body));
          } catch (_) {
            resolve(null);
          }
        });
      }
    );
    req.on("error", () => resolve(null));
    req.on("timeout", () => {
      req.destroy();
      resolve(null);
    });
  });
}

function fetchBinaryHttps(url, redirectsLeft = 3) {
  return new Promise((resolve) => {
    const req = https.get(
      url,
      {
        headers: {
          "User-Agent": "Zuna-Desktop/1.1.0 (https://github.com/debilxxx/Zuna-player)",
        },
        timeout: 7000,
      },
      (res) => {
        if (
          res.statusCode >= 300 &&
          res.statusCode < 400 &&
          res.headers.location &&
          redirectsLeft > 0
        ) {
          res.resume();
          const nextUrl = res.headers.location.startsWith("http")
            ? res.headers.location
            : new URL(res.headers.location, url).toString();
          fetchBinaryHttps(nextUrl, redirectsLeft - 1).then(resolve);
          return;
        }
        if (res.statusCode !== 200) {
          res.resume();
          resolve(null);
          return;
        }
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const buf = Buffer.concat(chunks);
          resolve(buf.length >= 1024 ? buf : null);
        });
      }
    );
    req.on("error", () => resolve(null));
    req.on("timeout", () => {
      req.destroy();
      resolve(null);
    });
  });
}

const onlineCoverNotFoundSet = new Set();
const onlineCoverInFlight = new Map();

function buildOnlineCoverCacheKey(artist, title, album) {
  const a = normalizeQueryString(artist || "").toLowerCase();
  const t = normalizeQueryString(title || "").toLowerCase();
  const al = normalizeQueryString(album || "").toLowerCase();
  const badAlbum = !al || al === "без альбома" || al === "unknown album" || al === "неизвестный";
  return `${a}::${t || (!badAlbum ? al : "")}`;
}

function isValidImageBuffer(buf) {
  if (!buf || buf.length < 1024) return false;
  const isJpg = buf[0] === 0xff && buf[1] === 0xd8;
  const isPng = buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47;
  const isWebp = buf.length > 12 && buf.slice(0, 4).toString("ascii") === "RIFF" && buf.slice(8, 12).toString("ascii") === "WEBP";
  return isJpg || isPng || isWebp;
}

async function resolveOnlineCoverForTrack(payload) {
  if (!payload || appState.settings.onlineCoversEnabled === false) return null;
  const targetTrack = (appState.tracks || []).find((t) => t.id === payload.id);
  if (targetTrack?.coverPath && fs.existsSync(targetTrack.coverPath)) return null;
  if (payload.coverPath && fs.existsSync(payload.coverPath)) return null;

  const title = (payload.title || targetTrack?.title || "").trim();
  const artist = (payload.artist || targetTrack?.artist || "").trim();
  const album = (payload.album || targetTrack?.album || "").trim();
  if (!title && !album) return null;

  const cacheKey = buildOnlineCoverCacheKey(artist, title, album);
  if (!cacheKey || cacheKey === "::") return null;

  const hash = crypto.createHash("sha1").update(cacheKey).digest("hex");
  const coverDir = getOnlineCoversCacheDir();
  const cachedFile = path.join(coverDir, `${hash}.jpg`);

  if (fs.existsSync(cachedFile)) {
    const thumb = ensureThumbForCover(cachedFile);
    if (targetTrack) {
      targetTrack.onlineCoverPath = cachedFile;
      targetTrack.onlineThumbPath = thumb;
    }
    return { trackId: payload.id, onlineCoverPath: cachedFile, onlineThumbPath: thumb };
  }

  if (onlineCoverNotFoundSet.has(cacheKey)) return null;
  if (onlineCoverInFlight.has(cacheKey)) {
    const res = await onlineCoverInFlight.get(cacheKey);
    if (!res) return null;
    if (targetTrack) {
      targetTrack.onlineCoverPath = res.onlineCoverPath;
      targetTrack.onlineThumbPath = res.onlineThumbPath;
    }
    return { trackId: payload.id, onlineCoverPath: res.onlineCoverPath, onlineThumbPath: res.onlineThumbPath };
  }

  const job = (async () => {
    try {
      const cleanTitle = normalizeQueryString(title) || title;
      const cleanArtist =
        artist && !/неизвест|unknown/i.test(artist) ? normalizeQueryString(artist) || artist : "";
      const cleanAlbum =
        album && !/без альбома|unknown/i.test(album) ? normalizeQueryString(album) || album : "";

      const queries = [];
      if (cleanArtist && cleanTitle) queries.push(`${cleanArtist} ${cleanTitle}`);
      if (cleanArtist && cleanAlbum && cleanAlbum !== cleanTitle) queries.push(`${cleanArtist} ${cleanAlbum}`);
      if (cleanTitle) queries.push(cleanTitle);

      let artUrl = null;
      for (const q of queries) {
        if (appState.settings.onlineCoversEnabled === false) return null;
        const data = await fetchJsonHttps(
          `https://itunes.apple.com/search?term=${encodeURIComponent(q)}&media=music&entity=song&limit=5`
        );
        const list = Array.isArray(data?.results) ? data.results : [];
        const matched =
          list.find((r) => {
            const rArt = (r.artistName || "").toLowerCase();
            return !cleanArtist || rArt.includes(cleanArtist.toLowerCase()) || cleanArtist.toLowerCase().includes(rArt);
          }) || list[0];
        if (matched?.artworkUrl100) {
          artUrl = matched.artworkUrl100.replace(/100x100bb/i, "600x600bb");
          break;
        }
      }

      if (!artUrl) {
        for (const q of queries.slice(0, 2)) {
          if (appState.settings.onlineCoversEnabled === false) return null;
          const dz = await fetchJsonHttps(
            `https://api.deezer.com/search?q=${encodeURIComponent(q)}&limit=3`
          );
          const list = Array.isArray(dz?.data) ? dz.data : [];
          const hit = list.find((r) => r?.album?.cover_xl || r?.album?.cover_big || r?.album?.cover_medium);
          if (hit) {
            artUrl = hit.album.cover_xl || hit.album.cover_big || hit.album.cover_medium;
            break;
          }
        }
      }

      if (!artUrl) {
        onlineCoverNotFoundSet.add(cacheKey);
        return null;
      }

      const imgBuf = await fetchBinaryHttps(artUrl);
      if (!isValidImageBuffer(imgBuf)) {
        onlineCoverNotFoundSet.add(cacheKey);
        return null;
      }

      fs.writeFileSync(cachedFile, imgBuf);
      const thumb = ensureThumbForCover(cachedFile);
      return { onlineCoverPath: cachedFile, onlineThumbPath: thumb };
    } catch (_) {
      return null;
    } finally {
      onlineCoverInFlight.delete(cacheKey);
    }
  })();

  onlineCoverInFlight.set(cacheKey, job);
  const resolved = await job;
  if (!resolved) return null;
  if (targetTrack && appState.settings.onlineCoversEnabled !== false) {
    targetTrack.onlineCoverPath = resolved.onlineCoverPath;
    targetTrack.onlineThumbPath = resolved.onlineThumbPath;
    savePersistedState();
  }
  return { trackId: payload.id, onlineCoverPath: resolved.onlineCoverPath, onlineThumbPath: resolved.onlineThumbPath };
}

function normalizeQueryString(str) {
  if (!str) return "";
  return str
    .replace(/\s*\([^)]*\)\s*/g, " ")
    .replace(/\s*\[[^\]]*\]\s*/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function findCaseInsensitiveSidecar(filePath) {
  try {
    const dir = path.dirname(filePath);
    const stemLower = path.basename(filePath, path.extname(filePath)).toLowerCase();
    const entries = fs.readdirSync(dir);

    for (const name of entries) {
      const ext = path.extname(name).toLowerCase();
      const base = path.basename(name, path.extname(name)).toLowerCase();
      if (base === stemLower && ext === ".lrc") {
        return { path: path.join(dir, name), type: "LOCAL .LRC" };
      }
    }
    for (const name of entries) {
      const ext = path.extname(name).toLowerCase();
      const base = path.basename(name, path.extname(name)).toLowerCase();
      if (base === stemLower && ext === ".txt") {
        return { path: path.join(dir, name), type: "LOCAL .TXT" };
      }
    }
  } catch (_) {}
  return null;
}

async function loadLyricsForTrack({ path: filePath, title, artist, album, durationMs }) {
  const sidecar = findCaseInsensitiveSidecar(filePath);
  if (sidecar) {
    try {
      const parsed = parseLyricsRawText(readTextFileSmart(sidecar.path), sidecar.type);
      if (parsed && parsed.isSynced) return parsed;
    } catch (_) {}
  }

  const normKey = `${(artist || "").toLowerCase().trim()}::${normalizeQueryString(title).toLowerCase()}`;
  if (lyricsMemoryCache.has(normKey)) {
    const cached = lyricsMemoryCache.get(normKey);
    if (cached && cached.isSynced) return cached;
  }

  let fallbackPlain = null;
  try {
    const mm = await import("music-metadata");
    const meta = await mm.parseFile(filePath, { skipCovers: true });
    if (meta.common.lyrics && meta.common.lyrics.length > 0) {
      const raw = meta.common.lyrics.join("\n");
      const parsed = parseLyricsRawText(raw, "EMBEDDED TAG");
      if (parsed) {
        if (parsed.isSynced) {
          lyricsMemoryCache.set(normKey, parsed);
          return parsed;
        }
        fallbackPlain = parsed;
      }
    }
  } catch (_) {}

  if (appState.settings.onlineLyricsEnabled !== false && title && title.trim()) {
    const durSec = Math.max(1, Math.round((durationMs || 0) / 1000));
    const cleanTitle = normalizeQueryString(title) || title;
    const cleanArtist = normalizeQueryString(artist || "") || artist || "";

    const candidates = [
      { t: title.trim(), a: (artist || "").trim() },
      { t: cleanTitle.toLowerCase(), a: cleanArtist.toLowerCase() },
    ];

    for (const cand of candidates) {
      const getUrl = `https://lrclib.net/api/get?track_name=${encodeURIComponent(
        cand.t
      )}&artist_name=${encodeURIComponent(cand.a)}&album_name=${encodeURIComponent(
        album || ""
      )}&duration=${durSec}`;

      const direct = await fetchJsonHttps(getUrl);
      if (direct) {
        if (direct.syncedLyrics && !direct.syncedLyrics.includes("\uFFFD")) {
          const parsed = parseLyricsRawText(direct.syncedLyrics, "LRCLIB");
          if (parsed) {
            lyricsMemoryCache.set(normKey, parsed);
            return parsed;
          }
        }
        if (direct.plainLyrics && !fallbackPlain) {
          fallbackPlain = parseLyricsRawText(direct.plainLyrics, "LRCLIB");
        }
      }

      const searchUrl = `https://lrclib.net/api/search?track_name=${encodeURIComponent(
        cand.t
      )}&artist_name=${encodeURIComponent(cand.a)}`;
      const list = await fetchJsonHttps(searchUrl);
      if (Array.isArray(list) && list.length > 0) {
        const syncedItems = list.filter((item) => item.syncedLyrics);
        if (syncedItems.length > 0) {
          // Prefer clean UTF-8 without replacement characters and closest duration
          syncedItems.sort((a, b) => {
            const aBad = a.syncedLyrics.includes("\uFFFD") ? 1 : 0;
            const bBad = b.syncedLyrics.includes("\uFFFD") ? 1 : 0;
            if (aBad !== bBad) return aBad - bBad;
            return (
              Math.abs((a.duration || durSec) - durSec) -
              Math.abs((b.duration || durSec) - durSec)
            );
          });
          const parsed = parseLyricsRawText(syncedItems[0].syncedLyrics, "LRCLIB");
          if (parsed) {
            lyricsMemoryCache.set(normKey, parsed);
            return parsed;
          }
        }
        if (!fallbackPlain && list[0].plainLyrics) {
          fallbackPlain = parseLyricsRawText(list[0].plainLyrics, "LRCLIB");
        }
      }
    }
  }

  if (fallbackPlain) {
    lyricsMemoryCache.set(normKey, fallbackPlain);
    return fallbackPlain;
  }

  return null;
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1380,
    height: 860,
    minWidth: 740,
    minHeight: 560,
    backgroundColor: "#000000",
    title: "Zuna",
    icon: path.join(__dirname, "assets", "icon.png"),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false,
      backgroundThrottling: false,
    },
  });

  mainWindow.setMenuBarVisibility(false);
  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
}

function setupMediaShortcuts() {
  try {
    globalShortcut.register("MediaPlayPause", () =>
      mainWindow?.webContents.send("media-action", "toggle")
    );
    globalShortcut.register("MediaNextTrack", () =>
      mainWindow?.webContents.send("media-action", "next")
    );
    globalShortcut.register("MediaPreviousTrack", () =>
      mainWindow?.webContents.send("media-action", "prev")
    );
  } catch (_) {}
}

function handleMprisAction(action) {
  if (!action || !action.type) return;
  if (action.type === "raise") {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
    return;
  }
  if (action.type === "quit") {
    app.quit();
    return;
  }
  mainWindow?.webContents.send("media-action", action);
}

if (gotSingleInstanceLock) {
  app.on("second-instance", () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    const eng = loadNativeAddon();
    if (eng) {
      try {
        eng.setUserVolume(appState.settings.volume ?? 0.85);
        eng.setReplayGainEnabled(Boolean(appState.settings.replayGainEnabled));
        eng.setGaplessEnabled(appState.settings.gaplessEnabled !== false);
        eng.setExclusiveMode(Boolean(appState.settings.exclusiveMode));
        if (appState.settings.outputDeviceId && appState.settings.outputDeviceId !== "default") {
          eng.setOutputDevice(appState.settings.outputDeviceId);
        }
        eng.updateEq(appState.eqConfig);
        if (typeof eng.mprisInit === "function") {
          eng.mprisInit(handleMprisAction);
        }
      } catch (_) {}
    }
    createMainWindow();
    setupMediaShortcuts();
  });
}

app.on("window-all-closed", () => {
  app.quit();
});

app.on("before-quit", () => {
  if (nativeEngine) {
    try {
      const st = nativeEngine.getStatus();
      if (st && st.positionSec > 0 && appState.lastPlayback?.trackId) {
        appState.lastPlayback.positionSec = st.positionSec;
        saveStateDirty = true;
      }
      if (typeof nativeEngine.mprisShutdown === "function") {
        nativeEngine.mprisShutdown();
      }
      nativeEngine.stop();
    } catch (_) {}
  }
  flushPersistedStateSync();
  globalShortcut.unregisterAll();
});

/* MINI SELF-CONTAINED ZIP WRITER & READER FOR .FLACTHEME PACKAGES */
const CRC32_TABLE = (() => {
  const tbl = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    tbl[i] = c >>> 0;
  }
  return tbl;
})();

function crc32Buf(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c = CRC32_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function buildThemeZipBuffer(entries) {
  // entries: [{ name: string, data: Buffer, compress?: boolean }]
  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const item of entries) {
    const nameBuf = Buffer.from(item.name, "utf-8");
    const raw = Buffer.isBuffer(item.data) ? item.data : Buffer.from(item.data);
    const crc = crc32Buf(raw);
    const useDeflate = Boolean(item.compress);
    const method = useDeflate ? 8 : 0;
    const payload = useDeflate ? zlib.deflateRawSync(raw, { level: 6 }) : raw;

    const lfh = Buffer.alloc(30);
    lfh.writeUInt32LE(0x04034b50, 0);
    lfh.writeUInt16LE(20, 4);
    lfh.writeUInt16LE(0x0800, 6); // UTF-8 flag
    lfh.writeUInt16LE(method, 8);
    lfh.writeUInt16LE(0, 10);
    lfh.writeUInt16LE(0, 12);
    lfh.writeUInt32LE(crc, 14);
    lfh.writeUInt32LE(payload.length, 18);
    lfh.writeUInt32LE(raw.length, 22);
    lfh.writeUInt16LE(nameBuf.length, 26);
    lfh.writeUInt16LE(0, 28);

    localParts.push(lfh, nameBuf, payload);

    const cdh = Buffer.alloc(46);
    cdh.writeUInt32LE(0x02014b50, 0);
    cdh.writeUInt16LE(20, 4);
    cdh.writeUInt16LE(20, 6);
    cdh.writeUInt16LE(0x0800, 8);
    cdh.writeUInt16LE(method, 10);
    cdh.writeUInt16LE(0, 12);
    cdh.writeUInt16LE(0, 14);
    cdh.writeUInt32LE(crc, 16);
    cdh.writeUInt32LE(payload.length, 20);
    cdh.writeUInt32LE(raw.length, 24);
    cdh.writeUInt16LE(nameBuf.length, 28);
    cdh.writeUInt16LE(0, 30);
    cdh.writeUInt16LE(0, 32);
    cdh.writeUInt16LE(0, 34);
    cdh.writeUInt16LE(0, 36);
    cdh.writeUInt32LE(0, 38);
    cdh.writeUInt32LE(offset, 42);

    centralParts.push(cdh, nameBuf);
    offset += lfh.length + nameBuf.length + payload.length;
  }

  const centralBuf = Buffer.concat(centralParts);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);

  return Buffer.concat([...localParts, centralBuf, eocd]);
}

const MAX_THEME_ZIP_ENTRY_BYTES = 150 * 1024 * 1024;
const SAFE_THEME_ASSET_EXTS = new Set([".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".mp4", ".webm", ".mov", ".mkv"]);

function sanitizeZipEntryName(rawName) {
  const normalized = String(rawName || "").replace(/\\/g, "/").replace(/^\/+/, "");
  const parts = normalized.split("/").filter(Boolean);
  if (!parts.length || parts.some((p) => p === "." || p === ".." || p.includes("\0"))) {
    throw new Error(`Недопустимое имя файла в теме: ${rawName}`);
  }
  return parts.join("/");
}

function parseThemeZipBuffer(buf) {
  let eocdOffset = -1;
  const minOff = Math.max(0, buf.length - 65558);
  for (let i = buf.length - 22; i >= minOff; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocdOffset = i;
      break;
    }
  }
  if (eocdOffset < 0) throw new Error("Неверный формат файла темы (.flactheme)");

  const totalEntries = buf.readUInt16LE(eocdOffset + 10);
  if (totalEntries > 64) throw new Error("Слишком много файлов внутри темы");
  let cdOffset = buf.readUInt32LE(eocdOffset + 16);
  const files = new Map();

  for (let i = 0; i < totalEntries; i++) {
    if (cdOffset + 46 > buf.length || buf.readUInt32LE(cdOffset) !== 0x02014b50) break;
    const method = buf.readUInt16LE(cdOffset + 10);
    const compSize = buf.readUInt32LE(cdOffset + 20);
    const uncompSize = buf.readUInt32LE(cdOffset + 24);
    const nameLen = buf.readUInt16LE(cdOffset + 28);
    const extraLen = buf.readUInt16LE(cdOffset + 30);
    const commentLen = buf.readUInt16LE(cdOffset + 32);
    const lfhOffset = buf.readUInt32LE(cdOffset + 42);
    if (uncompSize > MAX_THEME_ZIP_ENTRY_BYTES) {
      throw new Error("Файл внутри темы слишком большой");
    }
    if (cdOffset + 46 + nameLen > buf.length || lfhOffset + 30 > buf.length) {
      throw new Error("Повреждённый архив темы");
    }
    const name = sanitizeZipEntryName(
      buf.subarray(cdOffset + 46, cdOffset + 46 + nameLen).toString("utf-8")
    );

    const lfhNameLen = buf.readUInt16LE(lfhOffset + 26);
    const lfhExtraLen = buf.readUInt16LE(lfhOffset + 28);
    const dataStart = lfhOffset + 30 + lfhNameLen + lfhExtraLen;
    if (dataStart + compSize > buf.length) {
      throw new Error("Повреждённый архив темы");
    }
    const compData = buf.subarray(dataStart, dataStart + compSize);

    let rawData;
    if (method === 0) {
      rawData = Buffer.from(compData);
    } else if (method === 8) {
      rawData = zlib.inflateRawSync(compData, { maxOutputLength: MAX_THEME_ZIP_ENTRY_BYTES });
    } else {
      throw new Error(`Unsupported ZIP method: ${method}`);
    }

    files.set(name, rawData);
    cdOffset += 46 + nameLen + extraLen + commentLen;
  }
  return files;
}

const THEME_SETTING_KEYS = [
  "fontFamily",
  "visualizerEnabled",
  "visualizerStyle",
  "accentColor",
  "panelColor",
  "panelBrightness",
  "panelOpacity",
  "cardOpacity",
  "panelBlurPx",
  "borderRadiusPx",
  "borderOpacity",
  "lyricsBlurIntensity",
  "appBgMode",
  "appBgColorMode",
  "appBgSolidColor",
  "appBgIsVideo",
  "appBgBlurPx",
  "appBgDimPercent",
  "fsBgMode",
  "fsBgColorMode",
  "fsBgSolidColor",
  "fsBgIsVideo",
  "fsBgBlurPx",
  "fsBgDimPercent",
];

ipcMain.handle("get-initial-state", () => {
  let thumbUpdated = false;
  for (const t of appState.tracks || []) {
    if (t.coverPath && (!t.thumbPath || !fs.existsSync(t.thumbPath))) {
      t.thumbPath = ensureThumbForCover(t.coverPath);
      thumbUpdated = true;
    }
  }
  if (thumbUpdated) savePersistedState();
  return appState;
});

ipcMain.handle("export-theme", async (_, themeName) => {
  const safeBase = (themeName || "MyZunaTheme").trim().replace(/[^\p{L}\p{N}_\- ]/gu, "") || "ZunaTheme";
  const res = await dialog.showSaveDialog(mainWindow, {
    title: "Экспорт файла темы (.zunatheme)",
    defaultPath: `${safeBase}.zunatheme`,
    filters: [{ name: "Zuna Theme", extensions: ["zunatheme", "flactheme"] }],
  });
  if (res.canceled || !res.filePath) return { canceled: true };

  const s = appState.settings;
  const themePayload = {
    schemaVersion: 1,
    name: themeName || "Custom Theme",
    exportedAt: new Date().toISOString(),
    theme: {},
    assets: {},
  };

  for (const key of THEME_SETTING_KEYS) {
    themePayload.theme[key] = s[key];
  }

  const entries = [];
  const addedPaths = new Map(); // sourcePath -> zipAssetName

  const bundleMedia = (slotKey, sourcePath) => {
    if (!sourcePath || !fs.existsSync(sourcePath)) return;
    if (addedPaths.has(sourcePath)) {
      themePayload.assets[slotKey] = addedPaths.get(sourcePath);
      return;
    }
    try {
      const data = fs.readFileSync(sourcePath);
      const ext = path.extname(sourcePath).toLowerCase() || ".bin";
      const zipName = `assets/${slotKey}${ext}`;
      addedPaths.set(sourcePath, zipName);
      entries.push({ name: zipName, data, compress: false });
      themePayload.assets[slotKey] = zipName;
    } catch (_) {}
  };

  if (s.appBgMode === "custom" && s.appBgCustomPath) {
    bundleMedia("appBg", s.appBgCustomPath);
  }
  if (s.fsBgMode === "custom" && s.fsBgCustomPath) {
    bundleMedia("fsBg", s.fsBgCustomPath);
  }

  entries.unshift({
    name: "theme.json",
    data: Buffer.from(JSON.stringify(themePayload, null, 2), "utf-8"),
    compress: true,
  });

  const zipBuf = buildThemeZipBuffer(entries);
  fs.writeFileSync(res.filePath, zipBuf);
  return { canceled: false, filePath: res.filePath };
});

ipcMain.handle("import-theme", async (_, optionalPath) => {
  let filePath = optionalPath;
  if (!filePath) {
    const res = await dialog.showOpenDialog(mainWindow, {
      title: "Импорт темы (.zunatheme / .flactheme)",
      filters: [{ name: "Zuna Theme", extensions: ["zunatheme", "flactheme", "json"] }],
      properties: ["openFile"],
    });
    if (res.canceled || !res.filePaths.length) return { canceled: true };
    filePath = res.filePaths[0];
  }

  try {
    const rawBuf = fs.readFileSync(filePath);
    let parsed;
    let zipFiles = null;

    if (rawBuf[0] === 0x7b) {
      // Plain JSON fallback
      parsed = JSON.parse(rawBuf.toString("utf-8"));
    } else {
      zipFiles = parseThemeZipBuffer(rawBuf);
      const jsonBuf = zipFiles.get("theme.json");
      if (!jsonBuf) throw new Error("Внутри темы отсутствует theme.json");
      parsed = JSON.parse(jsonBuf.toString("utf-8"));
    }

    const themeObj = parsed.theme || parsed;
    const assetsDir = getThemesAssetsDir();

    for (const key of THEME_SETTING_KEYS) {
      if (themeObj[key] !== undefined) {
        appState.settings[key] = themeObj[key];
      }
    }

    const extractAsset = (slotKey, pathProp, isVideoProp) => {
      const rawZipName = parsed.assets && parsed.assets[slotKey];
      if (!rawZipName || !zipFiles) return;
      const zipName = sanitizeZipEntryName(rawZipName);
      if (!zipFiles.has(zipName)) return;
      const data = zipFiles.get(zipName);
      const extRaw = path.extname(zipName).toLowerCase();
      const ext = SAFE_THEME_ASSET_EXTS.has(extRaw) ? extRaw : ".png";
      const hash = crypto.createHash("sha1").update(data).digest("hex");
      const outPath = path.join(assetsDir, `${hash}${ext}`);
      if (!fs.existsSync(outPath)) {
        fs.writeFileSync(outPath, data);
      }
      appState.settings[pathProp] = outPath;
      appState.settings[isVideoProp] = [".mp4", ".webm", ".mov", ".mkv"].includes(ext);
    };

    extractAsset("appBg", "appBgCustomPath", "appBgIsVideo");
    extractAsset("fsBg", "fsBgCustomPath", "fsBgIsVideo");

    savePersistedState();
    return {
      canceled: false,
      name: parsed.name || path.basename(filePath, path.extname(filePath)),
      settings: appState.settings,
    };
  } catch (err) {
    return { canceled: false, error: err.message || "Ошибка чтения темы" };
  }
});

ipcMain.handle("pick-and-scan-folder", async () => {
  const res = await dialog.showOpenDialog(mainWindow, {
    title: "Выберите папку с музыкой",
    properties: ["openDirectory"],
  });
  if (res.canceled || !res.filePaths.length) return null;
  const folder = res.filePaths[0];
  if (!appState.folders.includes(folder)) {
    appState.folders.push(folder);
  }
  return await scanAllFolders();
});

ipcMain.handle("rescan-folders", async () => {
  return await scanAllFolders();
});

ipcMain.handle("remove-folder", async (_, folder) => {
  appState.folders = appState.folders.filter((f) => f !== folder);
  return await scanAllFolders();
});

ipcMain.handle("load-lyrics", async (_, payload) => {
  return await loadLyricsForTrack(payload);
});

ipcMain.handle("fetch-online-cover", async (_, payload) => {
  return await resolveOnlineCoverForTrack(payload);
});

ipcMain.handle("get-cover-data-url", async (_, coverPath) => {
  if (!coverPath || !fs.existsSync(coverPath)) return null;
  try {
    const buf = fs.readFileSync(coverPath);
    const mime = coverPath.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
    return `data:${mime};base64,${buf.toString("base64")}`;
  } catch (_) {
    return null;
  }
});

ipcMain.handle("pick-lrc-file", async () => {
  const res = await dialog.showOpenDialog(mainWindow, {
    title: "Выберите файл текста (.lrc или .txt)",
    filters: [{ name: "Lyrics", extensions: ["lrc", "txt"] }],
    properties: ["openFile"],
  });
  if (res.canceled || !res.filePaths.length) return null;
  const raw = readTextFileSmart(res.filePaths[0]);
  return parseLyricsRawText(raw, "MANUAL .LRC");
});

ipcMain.handle("pick-background-file", async () => {
  const res = await dialog.showOpenDialog(mainWindow, {
    title: "Выберите фото, GIF или видео для фона",
    filters: [
      {
        name: "Изображения и Видео",
        extensions: ["jpg", "jpeg", "png", "webp", "gif", "mp4", "webm", "mov", "mkv"],
      },
    ],
    properties: ["openFile"],
  });
  if (res.canceled || !res.filePaths.length) return null;
  const filePath = res.filePaths[0];
  const ext = path.extname(filePath).toLowerCase();
  const isVideo = [".mp4", ".webm", ".mov", ".mkv"].includes(ext);
  return { path: filePath, isVideo };
});

ipcMain.handle("record-track-played", (_, trackId) => {
  if (!trackId) return appState.recentlyPlayedIds;
  appState.recentlyPlayedIds = [
    trackId,
    ...(appState.recentlyPlayedIds || []).filter((id) => id !== trackId),
  ].slice(0, 200);
  savePersistedState();
  return appState.recentlyPlayedIds;
});

ipcMain.handle("clear-recently-played", () => {
  appState.recentlyPlayedIds = [];
  savePersistedState();
  return appState.recentlyPlayedIds;
});

ipcMain.handle("save-lyric-offset", (_, { trackId, offsetMs }) => {
  if (!trackId) return appState.lyricsOffsets || {};
  if (!appState.lyricsOffsets) appState.lyricsOffsets = {};
  if (!offsetMs || Math.abs(offsetMs) < 10) {
    delete appState.lyricsOffsets[trackId];
  } else {
    appState.lyricsOffsets[trackId] = Math.round(offsetMs);
  }
  savePersistedState();
  return appState.lyricsOffsets;
});

ipcMain.handle("toggle-favorite", (_, trackId) => {
  const idx = appState.favoriteIds.indexOf(trackId);
  if (idx >= 0) appState.favoriteIds.splice(idx, 1);
  else appState.favoriteIds.push(trackId);
  savePersistedState();
  return appState.favoriteIds;
});

ipcMain.handle("create-playlist", (_, name) => {
  const trimmed = (name || "").trim();
  if (trimmed) {
    appState.playlists.push({
      id: `pl_${Date.now()}`,
      name: trimmed,
      trackIds: [],
    });
    savePersistedState();
  }
  return appState.playlists;
});

ipcMain.handle("delete-playlist", (_, playlistId) => {
  appState.playlists = appState.playlists.filter((p) => p.id !== playlistId);
  savePersistedState();
  return appState.playlists;
});

ipcMain.handle("add-track-to-playlist", (_, { playlistId, trackId }) => {
  const pl = appState.playlists.find((p) => p.id === playlistId);
  if (pl && !pl.trackIds.includes(trackId)) {
    pl.trackIds.push(trackId);
    savePersistedState();
  }
  return appState.playlists;
});

ipcMain.handle("remove-track-from-playlist", (_, { playlistId, trackId }) => {
  const pl = appState.playlists.find((p) => p.id === playlistId);
  if (pl) {
    pl.trackIds = pl.trackIds.filter((id) => id !== trackId);
    savePersistedState();
  }
  return appState.playlists;
});

ipcMain.handle("save-eq-config", (_, eqConfig) => {
  appState.eqConfig = eqConfig;
  if (nativeEngine) nativeEngine.updateEq(eqConfig);
  savePersistedState();
  return appState.eqConfig;
});

ipcMain.handle("save-settings", (_, settings) => {
  appState.settings = settings;
  savePersistedState();
  return appState.settings;
});

ipcMain.handle("native-is-available", () => Boolean(loadNativeAddon()));
ipcMain.handle("native-get-devices", () => {
  const eng = loadNativeAddon();
  return eng ? eng.getDevices() : [];
});
ipcMain.handle("native-set-device", (_, deviceId) => {
  const eng = loadNativeAddon();
  return eng ? eng.setOutputDevice(deviceId || "default") : false;
});
ipcMain.handle("native-set-exclusive", (_, enabled) => {
  const eng = loadNativeAddon();
  return eng ? eng.setExclusiveMode(Boolean(enabled)) : false;
});
ipcMain.handle("native-load-file", (_, filePath) => {
  const eng = loadNativeAddon();
  return eng ? eng.loadFile(String(filePath || "")) : false;
});
ipcMain.handle("native-preload-next", (_, filePath) => {
  const eng = loadNativeAddon();
  return eng ? eng.preloadNextFile(String(filePath || "")) : false;
});
ipcMain.handle("native-start", () => {
  const eng = loadNativeAddon();
  return eng ? eng.start() : false;
});
ipcMain.handle("native-pause", () => {
  const eng = loadNativeAddon();
  return eng ? eng.pause() : true;
});
ipcMain.handle("native-stop", () => {
  const eng = loadNativeAddon();
  return eng ? eng.stop() : true;
});
ipcMain.handle("native-seek-seconds", (_, sec) => {
  const eng = loadNativeAddon();
  return eng ? eng.seekToSeconds(Number(sec) || 0) : false;
});
ipcMain.handle("native-set-volume", (_, vol) => {
  const eng = loadNativeAddon();
  return eng ? eng.setUserVolume(Number(vol)) : true;
});
ipcMain.handle("native-begin-pause-fade", (_, ms) => {
  const eng = loadNativeAddon();
  return eng ? eng.beginPauseFade(ms || 110) : true;
});
ipcMain.handle("native-cancel-pause-fade", () => {
  const eng = loadNativeAddon();
  return eng ? eng.cancelPauseFade() : true;
});
ipcMain.handle("native-start-crossfade", (_, ms) => {
  const eng = loadNativeAddon();
  return eng ? eng.startCrossfade(ms || 3000) : false;
});
ipcMain.handle("native-cancel-crossfade", () => {
  const eng = loadNativeAddon();
  return eng ? eng.cancelCrossfade() : true;
});
ipcMain.handle("native-promote-preloaded", () => {
  const eng = loadNativeAddon();
  return eng ? eng.promotePreloadedTrack() : false;
});
ipcMain.handle("native-set-replay-gain", (_, enabled) => {
  const eng = loadNativeAddon();
  return eng ? eng.setReplayGainEnabled(Boolean(enabled)) : true;
});
ipcMain.handle("native-set-gapless", (_, enabled) => {
  const eng = loadNativeAddon();
  return eng ? eng.setGaplessEnabled(Boolean(enabled)) : true;
});
ipcMain.handle("native-update-eq", (_, eqConfig) => {
  const eng = loadNativeAddon();
  return eng ? eng.updateEq(eqConfig) : true;
});
ipcMain.handle("native-get-status", () => {
  const eng = loadNativeAddon();
  return eng ? eng.getStatus() : null;
});

function updateLastPlaybackPayload(payload) {
  if (!payload || typeof payload !== "object") return;
  appState.lastPlayback = {
    trackId: payload.trackId || null,
    positionSec: Math.max(0, Number(payload.positionSec) || 0),
    queueIds: Array.isArray(payload.queueIds) ? payload.queueIds.slice(0, 5000) : [],
  };
}

ipcMain.on("save-playback-state", (_, payload) => {
  updateLastPlaybackPayload(payload);
  savePersistedState(false);
});

ipcMain.on("save-playback-state-now", (e, payload) => {
  updateLastPlaybackPayload(payload);
  savePersistedState(true);
  e.returnValue = true;
});

ipcMain.on("mpris-update", (_, payload) => {
  const eng = loadNativeAddon();
  if (!eng || typeof eng.mprisUpdate !== "function" || !payload || typeof payload !== "object") return;
  let artUrl = "";
  if (payload.coverPath && typeof payload.coverPath === "string") {
    try {
      if (fs.existsSync(payload.coverPath)) {
        artUrl = pathToFileURL(payload.coverPath).href;
      }
    } catch (_) {}
  }
  let trackUrl = "";
  if (payload.filePath && typeof payload.filePath === "string") {
    try {
      trackUrl = pathToFileURL(payload.filePath).href;
    } catch (_) {}
  }
  try {
    eng.mprisUpdate({
      hasTrack: Boolean(payload.hasTrack),
      trackId: String(payload.trackId || ""),
      title: String(payload.title || ""),
      artist: String(payload.artist || ""),
      album: String(payload.album || ""),
      artUrl,
      trackUrl,
      durationSec: Math.max(0, Number(payload.durationSec) || 0),
      positionSec: Math.max(0, Number(payload.positionSec) || 0),
      playbackStatus: String(payload.playbackStatus || "Stopped"),
      loopStatus: String(payload.loopStatus || "None"),
      shuffle: Boolean(payload.shuffle),
      volume: Math.max(0, Math.min(1, Number(payload.volume ?? 0.85))),
      seeked: Boolean(payload.seeked),
    });
  } catch (_) {}
});
