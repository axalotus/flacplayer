const api = window.flacApi;

const EQ_BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
const EQ_LABELS = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"];
const EQ_PRESETS = {
  "Flat": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  "Reference": [1.5, 1.0, 0.0, -0.5, 0.0, 0.5, 1.2, 2.0, 2.5, 3.0],
  "Harman Target": [3.5, 2.5, 1.0, -0.5, -0.5, 0.5, 1.5, 2.5, 2.0, 1.5],
  "Sub & Bass": [6.5, 5.5, 3.5, 1.0, 0.0, 0.0, 0.5, 1.0, 1.5, 1.5],
  "Deep Bass": [8.0, 6.5, 4.0, 1.5, -0.5, -0.5, 0.5, 1.5, 2.0, 2.0],
  "Punchy": [4.5, 5.0, 2.5, -1.0, -0.5, 0.5, 2.0, 3.0, 2.5, 2.0],
  "Club / Dance": [5.5, 4.5, 2.0, 0.0, 1.0, 2.0, 1.5, 2.5, 4.0, 4.5],
  "Electronic": [5.5, 4.5, 1.5, 0.0, -1.5, 1.5, 1.0, 2.5, 4.5, 5.0],
  "Hip-Hop / Rap": [6.5, 5.5, 2.5, -0.5, -1.0, 1.0, 2.0, 1.5, 3.0, 3.5],
  "Pop": [-1.0, 1.5, 3.5, 3.0, 1.5, 0.0, 1.0, 2.5, 3.0, 2.0],
  "Rock": [4.5, 3.5, 1.5, -1.0, -1.5, 1.0, 3.0, 4.0, 4.5, 4.5],
  "Metal": [5.0, 4.0, 1.0, -2.0, -2.5, 0.5, 3.5, 4.5, 5.0, 4.0],
  "Vocal": [-1.5, -1.0, 0.0, 2.0, 4.0, 4.5, 3.5, 1.5, 0.0, -0.5],
  "Acoustic": [3.0, 2.5, 1.5, 0.5, 1.0, 1.5, 2.5, 3.5, 3.0, 2.0],
  "R&B / Soul": [4.0, 5.0, 2.5, -1.0, -0.5, 1.5, 2.5, 2.0, 2.5, 3.5],
  "Jazz": [3.0, 2.5, 1.0, 1.5, -1.0, -1.0, 0.0, 1.5, 3.0, 3.5],
  "Classical": [3.5, 2.5, 1.5, 0.0, 0.0, 0.0, -1.0, 1.5, 3.0, 4.0],
  "Warm / Vinyl": [3.5, 3.0, 2.0, 1.5, 0.5, -0.5, -1.5, -2.0, -2.5, -3.5],
  "Bright / Airy": [-1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.5, 4.0, 5.5, 6.0],
  "Late Night": [2.5, 2.0, 0.5, -1.0, 0.5, 1.5, 1.5, 1.0, 0.5, 0.0],
};

/* DUAL-DECK BIT-TRANSPARENT AUDIO ENGINE WITH EQUAL-POWER CROSSFADE & ACTIVE-ONLY BIQUAD ROUTING */
function createAudioDeck(idx) {
  const el = new Audio();
  el.preload = "auto";
  el.crossOrigin = "anonymous";
  return { idx, el, sourceNode: null, gainNode: null };
}

const decks = [createAudioDeck(0), createAudioDeck(1)];
let activeDeckIdx = 0;
const getActiveDeck = () => decks[activeDeckIdx];
const getActiveAudio = () => decks[activeDeckIdx].el;

let audioCtx = null;
let mixNode = null;
let preampNode = null;
let bassNode = null;
let bandNodes = [];
let envelopeNode = null;
let softClipperNode = null;
let analyserNode = null;
let lastRoutingSignature = "";
let crossfadeState = null;
let pauseFadeToken = 0;

/* HYBRID ENGINE STATE: NATIVE C++ (FLAC/WAV/MP3 + MINIAUDIO DAC) + WEBAUDIO FALLBACK (OGG/M4A/OPUS) */
let activeBackend = "webaudio"; // "native" | "webaudio"
let nativeStatusCache = {
  isPlaying: false,
  isEof: false,
  nextEof: false,
  crossfadeActive: false,
  crossfadeCompleted: false,
  exclusiveRequested: false,
  exclusiveActual: false,
  positionSec: 0,
  durationSec: 0,
  sampleRate: 0,
  channels: 2,
  bitDepthOut: 0,
  backendName: "",
  activeDeviceName: "",
  lastError: "",
  spectrum: new Array(16).fill(0),
};
let nativePreloadAttemptedForId = null;
let nativePreloadSuccessForId = null;
let nativePreloadTrackRef = null;
let nativeCrossfadeStartedForId = null;
let nativeEndTriggeredForId = null;
let webaudioGaplessPreloadedForId = null;
let consecutiveTrackErrors = 0;
let playbackAnchorPosSec = 0;
let playbackAnchorPerf = 0;
let wordSyncRafId = 0;
let pendingResumeTrackId = null;
let pendingResumePositionSec = 0;
let lastPlaybackPersistMs = 0;

function persistPlaybackSnapshot(flushNow = false) {
  if (!state.currentTrack) return;
  const payload = {
    trackId: state.currentTrack.id,
    positionSec: getCurrentPlaybackTimeSec(),
    queueIds: (state.queue || []).map((t) => t.id),
  };
  lastPlaybackPersistMs = Date.now();
  if (flushNow && api.savePlaybackStateNow) {
    try { api.savePlaybackStateNow(payload); } catch (_) {}
  } else if (api.savePlaybackState) {
    api.savePlaybackState(payload);
  }
}

function syncPlaybackAnchor(posSec) {
  playbackAnchorPosSec = Number.isFinite(posSec) && posSec >= 0 ? posSec : 0;
  playbackAnchorPerf = performance.now();
}

function getSmoothPlaybackTimeSec() {
  if (state.currentTrack && pendingResumeTrackId === state.currentTrack.id) {
    return pendingResumePositionSec || 0;
  }
  if (activeBackend === "native") {
    const base = playbackAnchorPosSec || nativeStatusCache.positionSec || 0;
    if (!state.isPlaying || !playbackAnchorPerf) return base;
    const elapsed = Math.max(0, (performance.now() - playbackAnchorPerf) / 1000);
    const dur = getCurrentPlaybackDurationSec();
    const est = base + elapsed;
    return dur > 0 ? Math.min(dur, est) : est;
  }
  return getActiveAudio().currentTime || 0;
}

const NATIVE_SUPPORTED_FORMATS = new Set([
  "FLAC",
  "WAV",
  "MP3",
  "AAC",
  "M4A",
  "MP4",
  "OGG",
  "OGA",
  "OPUS",
  "ALAC",
  "AIFF",
  "AIF",
  "AIFC",
  "CAF",
]);

function isNativeTrackSupported(track) {
  if (!state.nativeAvailable || !track) return false;
  const fmt = String(track.format || "").toUpperCase();
  return NATIVE_SUPPORTED_FORMATS.has(fmt);
}

function getCurrentPlaybackTimeSec() {
  if (state.currentTrack && pendingResumeTrackId === state.currentTrack.id) {
    return pendingResumePositionSec || 0;
  }
  if (activeBackend === "native") return nativeStatusCache.positionSec || 0;
  return getActiveAudio().currentTime || 0;
}

function getCurrentPlaybackDurationSec() {
  if (activeBackend === "native") {
    if (nativeStatusCache.durationSec > 0) return nativeStatusCache.durationSec;
    return (state.currentTrack?.durationMs || 0) / 1000;
  }
  const d = getActiveAudio().duration;
  return Number.isFinite(d) && d > 0 ? d : (state.currentTrack?.durationMs || 0) / 1000;
}

function buildSoftClipperCurve(samples = 4096) {
  const curve = new Float32Array(samples);
  const kThresh = 0.988;
  const kHeadroom = 1.0 - kThresh;
  const maxIn = 3.0;
  for (let i = 0; i < samples; i++) {
    const x = ((i * 2) / (samples - 1) - 1) * maxIn;
    const ax = Math.abs(x);
    if (ax <= kThresh) {
      curve[i] = x;
    } else {
      const over = ax - kThresh;
      const limited = kThresh + kHeadroom * (over / (over + kHeadroom));
      curve[i] = x >= 0 ? limited : -limited;
    }
  }
  return curve;
}

function showToast(message, durationMs = 2600) {
  const el = document.getElementById("toast-banner");
  if (!el || !message) return;
  el.textContent = message;
  el.classList.remove("hidden");
  clearTimeout(showToast._timer);
  showToast._timer = setTimeout(() => el.classList.add("hidden"), durationMs);
}

function isEqEffectivelyActive() {
  const cfg = state.eqConfig;
  if (!cfg.enabled) return false;
  if (Math.abs(cfg.preampDb || 0) > 0.05) return true;
  if ((cfg.bassBoost || 0) > 0.01) return true;
  return cfg.bandGains.some((g) => Math.abs(g || 0) > 0.05);
}

function ensureAudioGraph() {
  if (audioCtx) {
    if (audioCtx.state === "suspended") audioCtx.resume();
    return;
  }
  const Ctx = window.AudioContext || window.webkitAudioContext;
  audioCtx = new Ctx({ latencyHint: "playback" });
  mixNode = audioCtx.createGain();
  mixNode.gain.value = 1;

  for (const deck of decks) {
    deck.sourceNode = audioCtx.createMediaElementSource(deck.el);
    deck.gainNode = audioCtx.createGain();
    deck.gainNode.gain.value = deck.idx === activeDeckIdx ? 1.0 : 0.0001;
    deck.sourceNode.connect(deck.gainNode);
    deck.gainNode.connect(mixNode);
  }

  preampNode = audioCtx.createGain();
  bassNode = audioCtx.createBiquadFilter();
  bassNode.type = "lowshelf";
  bassNode.frequency.value = 80;

  bandNodes = EQ_BANDS.map((freq, idx) => {
    const f = audioCtx.createBiquadFilter();
    f.type = idx === 0 ? "lowshelf" : idx === EQ_BANDS.length - 1 ? "highshelf" : "peaking";
    f.frequency.value = freq;
    f.Q.value = 1.414;
    f.gain.value = 0;
    return f;
  });

  envelopeNode = audioCtx.createGain();
  softClipperNode = audioCtx.createWaveShaper();
  softClipperNode.curve = buildSoftClipperCurve(4096);
  softClipperNode.oversample = "2x";
  analyserNode = audioCtx.createAnalyser();
  analyserNode.fftSize = 128;
  analyserNode.smoothingTimeConstant = 0.8;

  envelopeNode.connect(softClipperNode);
  softClipperNode.connect(analyserNode);
  analyserNode.connect(audioCtx.destination);

  applyEqToAudioGraph(true);
  applyVolumeToAudioGraph();
}

function applyEqToAudioGraph(forceRebuild = false) {
  if (!audioCtx || !mixNode) return;
  const cfg = state.eqConfig;
  const active = isEqEffectivelyActive();

  const activeMask = active
    ? `1:${Math.abs(cfg.preampDb) > 0.05 ? 1 : 0}:${cfg.bassBoost > 0.01 ? 1 : 0}:` +
      cfg.bandGains.map((g) => (Math.abs(g) > 0.05 ? 1 : 0)).join("")
    : "0:direct";

  if (forceRebuild || activeMask !== lastRoutingSignature) {
    lastRoutingSignature = activeMask;
    try {
      mixNode.disconnect();
      preampNode.disconnect();
      bassNode.disconnect();
      bandNodes.forEach((b) => b.disconnect());
    } catch (_) {}

    if (!active) {
      mixNode.connect(envelopeNode);
    } else {
      let current = mixNode;
      if (Math.abs(cfg.preampDb || 0) > 0.05) {
        current.connect(preampNode);
        current = preampNode;
      }
      if ((cfg.bassBoost || 0) > 0.01) {
        current.connect(bassNode);
        current = bassNode;
      }
      for (let i = 0; i < bandNodes.length; i++) {
        if (Math.abs(cfg.bandGains[i] || 0) > 0.05) {
          current.connect(bandNodes[i]);
          current = bandNodes[i];
        }
      }
      current.connect(envelopeNode);
    }
  }

  if (active) {
    const now = audioCtx.currentTime;
    const preampLin = Math.pow(10, (cfg.preampDb || 0) / 20);
    preampNode.gain.setTargetAtTime(preampLin, now, 0.015);
    bassNode.gain.setTargetAtTime((cfg.bassBoost || 0) * 12.0, now, 0.015);
    for (let i = 0; i < bandNodes.length; i++) {
      bandNodes[i].gain.setTargetAtTime(cfg.bandGains[i] || 0, now, 0.015);
    }
  }
}

function getEnvelopeTargetGain() {
  return state.settings.replayGainEnabled ? 0.92 : 1.0;
}

function applyVolumeToAudioGraph() {
  const userVol = Math.max(0, Math.min(1, state.settings.volume ?? 0.85));
  for (const d of decks) d.el.volume = userVol;
  if (state.nativeAvailable) {
    api.nativeSetVolume(userVol);
  }
  if (audioCtx && envelopeNode && activeBackend === "webaudio" && state.isPlaying) {
    envelopeNode.gain.setTargetAtTime(getEnvelopeTargetGain(), audioCtx.currentTime, 0.02);
  }
  pushNativeMprisState(false);
}

function cancelActiveCrossfade(snapGains = true) {
  if (crossfadeState) {
    clearInterval(crossfadeState.timerId);
    const oldDeck = decks[crossfadeState.fromIdx];
    oldDeck.el.pause();
    oldDeck.el.removeAttribute("src");
    oldDeck.el.load();
    crossfadeState = null;
  }
  if (snapGains && audioCtx) {
    const now = audioCtx.currentTime;
    for (const d of decks) {
      if (d.gainNode) {
        d.gainNode.gain.cancelScheduledValues(now);
        d.gainNode.gain.setValueAtTime(d.idx === activeDeckIdx ? 1.0 : 0.0001, now);
      }
    }
  }
}

let state = {
  folders: [],
  tracks: [],
  favoriteIds: [],
  recentlyPlayedIds: [],
  lyricsOffsets: {},
  playlists: [],
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
    fontFamily: "jakarta",
    visualizerEnabled: true,
    visualizerStyle: "bars",
    accentColor: "#ffffff",
    panelColor: "#0a0a0a",
    panelBrightness: 100,
    panelOpacity: 88,
    cardOpacity: 86,
    panelBlurPx: 14,
    borderRadiusPx: 12,
    borderOpacity: 10,
    lyricsBlurIntensity: 100,
    appBgMode: "none",
    appBgColorMode: "mono",
    appBgSolidColor: "#000000",
    appBgCustomPath: "",
    appBgIsVideo: false,
    appBgBlurPx: 48,
    appBgDimPercent: 68,
    fsBgMode: "cover",
    fsBgColorMode: "mono",
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
  nativeAvailable: false,
  isStopped: false,
  systemBatteryLow: false,
  batteryInfoKnown: false,
  batteryLevelPct: 100,
  batteryCharging: true,
  settingsTab: "customization",
  duplicatesExpanded: false,
  sleepMode: "off", // "off" | "timer" | "end_of_track"
  sleepEndTimeMs: 0,
  sleepTimerId: null,
  currentView: "home",
  navHistory: [],
  selectedArtistName: "",
  drilldownTitle: "",
  drilldownTracks: [],
  activePlaylistId: null,
  searchQuery: "",
  sortMode: "artist",
  queue: [],
  originalQueue: [],
  currentIndex: -1,
  currentTrack: null,
  isPlaying: false,
  isShuffle: false,
  repeatMode: "off",
  isSeeking: false,
  lyrics: null,
  activeLyricIndex: -2,
  manualLyricsScroll: false,
  manualScrollTimer: null,
  crossfadeTriggeredForId: null,
  trackListLimit: 200,
  pendingTrackForNewPlaylist: null,
};

let settingsSaveTimer = null;
function scheduleSaveSettings() {
  if (settingsSaveTimer) clearTimeout(settingsSaveTimer);
  settingsSaveTimer = setTimeout(() => {
    settingsSaveTimer = null;
    api.saveSettings(state.settings);
  }, 180);
}

function shuffleArray(items) {
  const arr = [...items];
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

/* CUSTOM SLIDER FILL HELPER */
function updateSliderFill(sliderEl) {
  if (!sliderEl) return;
  const min = parseFloat(sliderEl.min || 0);
  const max = parseFloat(sliderEl.max || 100);
  const val = parseFloat(sliderEl.value || 0);
  const pct = max > min ? ((val - min) / (max - min)) * 100 : 0;
  sliderEl.style.setProperty("--fill-pct", `${Math.max(0, Math.min(100, pct))}%`);
}

function toFileUrl(filePath) {
  if (!filePath) return "";
  const normalized = filePath.replace(/\\/g, "/");
  return encodeURI(`file://${normalized.startsWith("/") ? "" : "/"}${normalized}`).replace(/#/g, "%23");
}

function formatTime(ms) {
  if (!ms || !Number.isFinite(ms) || ms < 0) return "0:00";
  const totalSec = Math.floor(ms / 1000);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${sec < 10 ? "0" : ""}${sec}`;
}

function formatTrackBadge(t) {
  const sr = ((t.sampleRateHz || 44100) / 1000).toFixed(1);
  if (
    t.format === "FLAC" ||
    t.format === "WAV" ||
    t.format === "ALAC" ||
    t.format === "AIFF" ||
    t.format === "AIF" ||
    t.format === "AIFC"
  ) {
    return `${t.format} • ${t.bitDepth || 16}-BIT / ${sr} KHZ`;
  }
  const brPart = t.bitrateKbps ? `${t.bitrateKbps} KBPS / ` : "";
  return `${t.format} • ${brPart}${sr} KHZ`;
}

function escapeHtml(str) {
  return String(str ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

const THEME_PRESETS = [
  {
    id: "monochrome",
    name: "Monochrome",
    accentColor: "#ffffff",
    panelColor: "#0a0a0a",
    panelBrightness: 100,
    panelOpacity: 88,
    cardOpacity: 86,
    panelBlurPx: 14,
    borderRadiusPx: 12,
    borderOpacity: 10,
  },
  {
    id: "obsidian-crimson",
    name: "Crimson Dark",
    accentColor: "#ff3b5c",
    panelColor: "#140b0e",
    panelBrightness: 110,
    panelOpacity: 84,
    cardOpacity: 80,
    panelBlurPx: 18,
    borderRadiusPx: 14,
    borderOpacity: 12,
  },
  {
    id: "nordic-frost",
    name: "Nordic Frost",
    accentColor: "#7dd3fc",
    panelColor: "#0c131d",
    panelBrightness: 115,
    panelOpacity: 82,
    cardOpacity: 78,
    panelBlurPx: 20,
    borderRadiusPx: 14,
    borderOpacity: 13,
  },
  {
    id: "amber-studio",
    name: "Amber Studio",
    accentColor: "#f59e0b",
    panelColor: "#141008",
    panelBrightness: 112,
    panelOpacity: 88,
    cardOpacity: 84,
    panelBlurPx: 14,
    borderRadiusPx: 10,
    borderOpacity: 12,
  },
  {
    id: "emerald-glass",
    name: "Emerald Glass",
    accentColor: "#34d399",
    panelColor: "#081410",
    panelBrightness: 110,
    panelOpacity: 80,
    cardOpacity: 76,
    panelBlurPx: 18,
    borderRadiusPx: 16,
    borderOpacity: 14,
  },
];

function hexToRgb(hex) {
  const clean = String(hex || "").replace("#", "").trim();
  const full =
    clean.length === 3
      ? clean
          .split("")
          .map((c) => c + c)
          .join("")
      : clean;
  if (!/^[0-9a-fA-F]{6}$/.test(full)) return { r: 10, g: 10, b: 10 };
  return {
    r: parseInt(full.slice(0, 2), 16),
    g: parseInt(full.slice(2, 4), 16),
    b: parseInt(full.slice(4, 6), 16),
  };
}

function getContrastFg(hex) {
  const { r, g, b } = hexToRgb(hex);
  const lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  return lum > 0.54 ? "#000000" : "#ffffff";
}

function getAccentCssColor() {
  return state.settings.accentColor || "#ffffff";
}

function getEffectivePowerSaver() {
  const s = state.settings || {};
  const mode = s.powerSaverMode || "standard";
  const autoTriggered = mode === "auto" && Boolean(state.systemBatteryLow);
  const fullSaver = autoTriggered;
  const isBalanced = mode === "balanced";
  return {
    mode,
    autoTriggered,
    isBalanced,
    active:
      fullSaver ||
      isBalanced ||
      Boolean(
        s.powerDisableBlur ||
          s.powerReduceAnimations ||
          s.powerDisableDynamicBg ||
          s.powerDisableVisualizer ||
          s.powerLimitUiRefresh ||
          s.powerUseThumbCovers
      ),
    disableBlur: fullSaver || Boolean(s.powerDisableBlur),
    reduceAnimations: fullSaver || isBalanced || Boolean(s.powerReduceAnimations),
    disableDynamicBg: fullSaver || Boolean(s.powerDisableDynamicBg),
    disableVisualizer: fullSaver || Boolean(s.powerDisableVisualizer),
    limitUiRefresh: fullSaver || isBalanced || Boolean(s.powerLimitUiRefresh),
    useThumbCovers: fullSaver || Boolean(s.powerUseThumbCovers),
  };
}

function applyPowerSaverPreset(mode) {
  const s = state.settings;
  s.powerSaverMode = mode;
  if (mode === "standard") {
    s.powerDisableBlur = false;
    s.powerReduceAnimations = false;
    s.powerDisableDynamicBg = false;
    s.powerDisableVisualizer = false;
    s.powerLimitUiRefresh = false;
    s.powerUseThumbCovers = false;
  } else if (mode === "balanced") {
    s.powerDisableBlur = false;
    s.powerReduceAnimations = true;
    s.powerDisableDynamicBg = false;
    s.powerDisableVisualizer = false;
    s.powerLimitUiRefresh = true;
    s.powerUseThumbCovers = false;
  } else if (mode === "auto") {
    s.powerDisableBlur = false;
    s.powerReduceAnimations = false;
    s.powerDisableDynamicBg = false;
    s.powerDisableVisualizer = false;
    s.powerLimitUiRefresh = false;
    s.powerUseThumbCovers = false;
  } else if (mode === "battery_saver") {
    s.powerDisableBlur = true;
    s.powerReduceAnimations = true;
    s.powerDisableDynamicBg = true;
    s.powerDisableVisualizer = true;
    s.powerLimitUiRefresh = true;
    s.powerUseThumbCovers = true;
  }
}

let scheduleVisualizerDraw = null;

function applyAppearanceSettings() {
  const s = state.settings;
  const ps = getEffectivePowerSaver();
  document.body.classList.remove(
    "font-jakarta",
    "font-manrope",
    "font-inter",
    "font-mono",
    "font-system"
  );
  document.body.classList.add(`font-${s.fontFamily || "jakarta"}`);
  document.body.classList.toggle("ps-no-blur", ps.disableBlur);
  document.body.classList.toggle("ps-low-anim", ps.reduceAnimations);
  document.body.classList.toggle("ps-no-dynamic-bg", ps.disableDynamicBg);

  const root = document.documentElement.style;
  const accent = s.accentColor || "#ffffff";
  const accentFg = getContrastFg(accent);
  root.setProperty("--accent", accent);
  root.setProperty("--accent-fg", accentFg);

  const pRgb = hexToRgb(s.panelColor || "#0a0a0a");
  const brightFactor = (s.panelBrightness ?? 100) / 100;
  const scaleC = (v) => Math.max(0, Math.min(255, Math.round(v * brightFactor)));
  const pr = scaleC(pRgb.r);
  const pg = scaleC(pRgb.g);
  const pb = scaleC(pRgb.b);

  const pOp = Math.max(0.2, Math.min(1, (s.panelOpacity ?? 88) / 100));
  const cOp = Math.max(0.2, Math.min(1, (s.cardOpacity ?? 86) / 100));
  const underRgb =
    (s.appBgMode || "none") === "none" ? hexToRgb(s.appBgSolidColor || "#000000") : { r: 10, g: 10, b: 10 };
  const effR = pr * pOp + underRgb.r * (1 - pOp);
  const effG = pg * pOp + underRgb.g * (1 - pOp);
  const effB = pb * pOp + underRgb.b * (1 - pOp);
  const surfaceLum = (0.2126 * effR + 0.7152 * effG + 0.0722 * effB) / 255;
  const isLightSurface = surfaceLum > 0.52;

  const liftC = (v, amt) => Math.max(0, Math.min(255, v + (isLightSurface ? -amt : amt)));

  root.setProperty("--bg-1", `rgba(${pr}, ${pg}, ${pb}, ${pOp.toFixed(2)})`);
  root.setProperty("--card-bg", `rgba(${liftC(pr, 4)}, ${liftC(pg, 4)}, ${liftC(pb, 4)}, ${cOp.toFixed(2)})`);
  root.setProperty(
    "--bg-2",
    `rgba(${liftC(pr, 18)}, ${liftC(pg, 18)}, ${liftC(pb, 18)}, ${Math.min(1, pOp + 0.05).toFixed(2)})`
  );
  root.setProperty("--bg-3", `rgba(${liftC(pr, 30)}, ${liftC(pg, 30)}, ${liftC(pb, 30)}, 0.92)`);
  root.setProperty("--bg-hover", `rgba(${liftC(pr, 38)}, ${liftC(pg, 38)}, ${liftC(pb, 38)}, 0.95)`);

  root.setProperty("--fg-0", isLightSurface ? "#111111" : "#ffffff");
  root.setProperty("--fg-1", isLightSurface ? "#2e2e2e" : "#c8c8c8");
  root.setProperty("--fg-2", isLightSurface ? "#555555" : "#7e7e7e");
  root.setProperty("--fg-3", isLightSurface ? "#858585" : "#484848");
  root.setProperty("--select-opt-bg", isLightSurface ? "#f2f2f2" : "#141414");
  root.setProperty("--select-opt-fg", isLightSurface ? "#111111" : "#ffffff");

  const rawPanelBlur = s.panelBlurPx ?? 14;
  const effPanelBlur = ps.disableBlur ? 0 : ps.isBalanced ? Math.min(8, Math.round(rawPanelBlur * 0.5)) : rawPanelBlur;
  root.setProperty("--panel-blur", effPanelBlur > 0 ? `blur(${effPanelBlur}px)` : "none");

  const rad = s.borderRadiusPx ?? 12;
  root.setProperty("--r-sm", `${Math.max(2, Math.round(rad * 0.68))}px`);
  root.setProperty("--r-md", `${rad}px`);
  root.setProperty("--r-lg", `${Math.max(4, Math.round(rad * 1.25))}px`);

  const bOp = Math.max(0, Math.min(0.45, (s.borderOpacity ?? 10) / 100));
  const lineRgb = isLightSurface ? "0, 0, 0" : "255, 255, 255";
  root.setProperty("--line", `rgba(${lineRgb}, ${bOp.toFixed(2)})`);
  root.setProperty("--line-strong", `rgba(${lineRgb}, ${Math.min(0.6, bOp * 2.2).toFixed(2)})`);

  const rawAppBlur = s.appBgBlurPx ?? s.bgBlurPx ?? 48;
  const appBlur = ps.disableBlur ? 0 : ps.isBalanced ? Math.round(rawAppBlur * 0.45) : rawAppBlur;
  const appDim = s.appBgDimPercent ?? s.bgDimPercent ?? 68;
  const appGray = (s.appBgColorMode || "mono") === "color" ? "0%" : "100%";

  const rawFsBlur = s.fsBgBlurPx ?? s.bgBlurPx ?? 52;
  const fsBlur = ps.disableBlur ? 0 : ps.isBalanced ? Math.round(rawFsBlur * 0.45) : rawFsBlur;
  const fsDim = s.fsBgDimPercent ?? s.bgDimPercent ?? 64;
  const fsGray = (s.fsBgColorMode || "mono") === "color" ? "0%" : "100%";

  root.setProperty("--app-bg-blur", `${appBlur}px`);
  root.setProperty("--app-bg-dim", `${appDim / 100}`);
  root.setProperty("--app-bg-gray", appGray);

  root.setProperty("--fs-bg-blur", `${fsBlur}px`);
  root.setProperty("--fs-bg-dim", `${fsDim / 100}`);
  root.setProperty("--fs-bg-gray", fsGray);

  const specCanvas = document.getElementById("mini-spectrum");
  specCanvas.classList.toggle("hidden", !s.visualizerEnabled || ps.disableVisualizer);

  updateSidebarCompactState();
  updateBackgroundLayers();
  updateCountsAndBadges();
  updateBarNowPlaying();
  drawEqResponseCurve();
  if (typeof scheduleVisualizerDraw === "function") scheduleVisualizerDraw();
}

function updateSidebarCompactState() {
  const sidebar = document.getElementById("left-sidebar");
  const isNarrow = window.innerWidth < 1100;
  const compact = Boolean(state.settings.sidebarCompact) || isNarrow;
  sidebar.classList.toggle("rail-mode", compact);
}

function getEffectiveTrackCover(track, preferFull = false) {
  if (!track) return null;
  const useThumb = preferFull && getEffectivePowerSaver().useThumbCovers;
  const wantFull = preferFull && !useThumb;
  if (track.coverPath) return wantFull ? track.coverPath : (track.thumbPath || track.coverPath);
  if (state.settings.onlineCoversEnabled !== false && track.onlineCoverPath) {
    return wantFull ? track.onlineCoverPath : (track.onlineThumbPath || track.onlineCoverPath);
  }
  return null;
}

const onlineCoverRequestedIds = new Set();
const onlineCoverQueue = [];
let onlineCoverActiveWorkers = 0;

function applyOnlineCoverToMountedDom(track) {
  const thumb = getEffectiveTrackCover(track, false);
  if (!thumb) return;
  const src = toFileUrl(thumb);
  document.querySelectorAll(`.track-item[data-track-id="${CSS.escape(track.id)}"] .track-art`).forEach((artEl) => {
    artEl.innerHTML = `<img src="${src}" loading="lazy" decoding="async" alt="" />`;
  });
  document.querySelectorAll(`[data-cover-track-id="${CSS.escape(track.id)}"] .h-card-art, [data-cover-track-id="${CSS.escape(track.id)}"] .grid-art`).forEach((artEl) => {
    artEl.innerHTML = `<img src="${src}" loading="lazy" decoding="async" draggable="false" alt="" />`;
  });
  if (state.currentTrack?.id === track.id) {
    updateBarNowPlaying();
    updateBackgroundLayers();
    syncMprisMediaSession(state.currentTrack);
  }
}

async function pumpOnlineCoverQueue() {
  while (onlineCoverActiveWorkers < 2 && onlineCoverQueue.length > 0) {
    if (state.settings.onlineCoversEnabled === false) {
      onlineCoverQueue.length = 0;
      return;
    }
    const track = onlineCoverQueue.shift();
    if (!track || track.coverPath || track.onlineCoverPath) continue;
    onlineCoverActiveWorkers++;
    api.fetchOnlineCover({
      id: track.id,
      title: track.title,
      artist: track.artist,
      album: track.album,
      coverPath: track.coverPath,
    })
      .then((res) => {
        if (res && res.onlineCoverPath && state.settings.onlineCoversEnabled !== false) {
          track.onlineCoverPath = res.onlineCoverPath;
          track.onlineThumbPath = res.onlineThumbPath || res.onlineCoverPath;
          state.tracks.forEach((tr) => {
            if (tr.id === track.id) {
              tr.onlineCoverPath = track.onlineCoverPath;
              tr.onlineThumbPath = track.onlineThumbPath;
            }
          });
          applyOnlineCoverToMountedDom(track);
        }
      })
      .catch(() => {})
      .finally(() => {
        onlineCoverActiveWorkers--;
        if (onlineCoverQueue.length > 0) pumpOnlineCoverQueue();
      });
  }
}

function requestOnlineCoverForTrack(track, prioritize = false) {
  if (!track || track.coverPath || track.onlineCoverPath) return;
  if (state.settings.onlineCoversEnabled === false) return;
  if (onlineCoverRequestedIds.has(track.id)) return;
  onlineCoverRequestedIds.add(track.id);
  if (prioritize) onlineCoverQueue.unshift(track);
  else onlineCoverQueue.push(track);
  pumpOnlineCoverQueue();
}

function updateBackgroundLayers() {
  const s = state.settings;
  const ps = getEffectivePowerSaver();
  const effCover = getEffectiveTrackCover(state.currentTrack, !ps.useThumbCovers);
  const coverUrl = effCover ? toFileUrl(effCover) : "";
  const appDim = s.appBgDimPercent ?? s.bgDimPercent ?? 68;
  const fsDim = s.fsBgDimPercent ?? s.bgDimPercent ?? 64;
  const fsOpen = document.body.classList.contains("fs-open");
  const pageVisible = !document.hidden;

  const applyLayer = (mode, customPath, isVideo, dimPct, solidColor, imgId, vidId, scrimId, layerVisible) => {
    const imgEl = document.getElementById(imgId);
    const vidEl = document.getElementById(vidId);
    const scrimEl = document.getElementById(scrimId);

    if (mode === "none" || (ps.disableDynamicBg && mode === "custom" && isVideo)) {
      imgEl.classList.add("hidden");
      vidEl.classList.add("hidden");
      vidEl.pause();
      if (ps.disableDynamicBg && coverUrl) {
        scrimEl.style.background = `rgba(0, 0, 0, ${dimPct / 100})`;
        imgEl.src = coverUrl;
        imgEl.classList.remove("hidden");
      } else {
        scrimEl.style.background = solidColor || "#000000";
      }
      return;
    }

    scrimEl.style.background = `rgba(0, 0, 0, ${dimPct / 100})`;

    if (mode === "cover") {
      vidEl.classList.add("hidden");
      vidEl.pause();
      if (coverUrl) {
        imgEl.src = coverUrl;
        imgEl.classList.remove("hidden");
      } else {
        imgEl.classList.add("hidden");
      }
    } else if (mode === "custom" && customPath) {
      const fileUrl = toFileUrl(customPath);
      if (isVideo) {
        imgEl.classList.add("hidden");
        if (vidEl.src !== fileUrl) vidEl.src = fileUrl;
        vidEl.classList.remove("hidden");
        if (layerVisible && !ps.disableDynamicBg) {
          vidEl.play().catch(() => {});
        } else {
          vidEl.pause();
        }
      } else {
        vidEl.classList.add("hidden");
        vidEl.pause();
        imgEl.src = fileUrl;
        imgEl.classList.remove("hidden");
      }
    } else {
      imgEl.classList.add("hidden");
      vidEl.classList.add("hidden");
      vidEl.pause();
    }
  };

  applyLayer(
    s.appBgMode || "none",
    s.appBgCustomPath,
    s.appBgIsVideo,
    appDim,
    s.appBgSolidColor,
    "app-bg-img",
    "app-bg-video",
    "app-bg-scrim",
    pageVisible && !fsOpen
  );
  applyLayer(
    s.fsBgMode || "cover",
    s.fsBgCustomPath,
    s.fsBgIsVideo,
    fsDim,
    s.fsBgSolidColor,
    "fs-bg-img",
    "fs-bg-video",
    "fs-bg-scrim",
    pageVisible && fsOpen
  );
}

function computeLibraryVisibility() {
  const s = state.settings;
  const hiddenSet = new Set(s.hiddenTrackIds || []);
  const forceVisibleDupSet = new Set(s.forceVisibleDuplicateIds || []);
  const hideDups = Boolean(s.hideDuplicates);

  const nonHidden = (state.tracks || []).filter((t) => !hiddenSet.has(t.id));
  if (!hideDups) {
    return { visibleTracks: nonHidden, duplicateEntries: [] };
  }

  const byKey = new Map();
  for (const t of nonHidden) {
    const key = `${(t.artist || "").trim().toLowerCase()}||${(t.title || "").trim().toLowerCase()}||${Math.round((t.durationMs || 0) / 2000)}`;
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push(t);
  }

  const visibleTracks = [];
  const duplicateEntries = [];
  for (const group of byKey.values()) {
    if (group.length === 1) {
      visibleTracks.push(group[0]);
      continue;
    }
    const sorted = [...group].sort((a, b) => (b.sampleRateHz || 0) * (b.bitDepth || 16) - (a.sampleRateHz || 0) * (a.bitDepth || 16));
    const keeper = sorted[0];
    visibleTracks.push(keeper);
    for (let i = 1; i < sorted.length; i++) {
      const dup = sorted[i];
      if (forceVisibleDupSet.has(dup.id)) {
        visibleTracks.push(dup);
      } else {
        duplicateEntries.push({ track: dup, keptTrack: keeper });
      }
    }
  }
  return { visibleTracks, duplicateEntries };
}

function getVisibleTracks() {
  return computeLibraryVisibility().visibleTracks;
}

function updateCountsAndBadges() {
  document.getElementById("nav-count-tracks").textContent = getVisibleTracks().length;
  document.getElementById("nav-count-favs").textContent = state.favoriteIds.length;
  document.getElementById("nav-eq-badge").textContent = state.eqConfig.enabled ? "ON" : "OFF";
}

function renderSidebarPlaylists() {
  const box = document.getElementById("sidebar-playlists");
  box.innerHTML = "";
  state.playlists.forEach((pl) => {
    const btn = document.createElement("button");
    const isActive = state.currentView === "playlist" && state.activePlaylistId === pl.id;
    btn.className = `nav-link ${isActive ? "active" : ""}`;
    btn.title = pl.name;
    btn.innerHTML = `
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.9">
        <path d="M9 18V5l12-2v13"></path>
        <circle cx="6" cy="18" r="3"></circle>
        <circle cx="18" cy="16" r="3"></circle>
      </svg>
      <span class="nav-text" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escapeHtml(pl.name)}</span>
      <span class="mono-count nav-text">${pl.trackIds.length}</span>
    `;
    btn.onclick = () => {
      state.activePlaylistId = pl.id;
      navigateToView("playlist", false);
    };
    box.appendChild(btn);
  });
}

function navigateToView(viewName, pushHistory = true) {
  if (pushHistory && state.currentView !== viewName) {
    state.navHistory.push({
      view: state.currentView,
      selectedArtistName: state.selectedArtistName,
      drilldownTitle: state.drilldownTitle,
      drilldownTracks: state.drilldownTracks,
      activePlaylistId: state.activePlaylistId,
    });
  }
  state.currentView = viewName;
  state.trackListLimit = 200;
  const backBtn = document.getElementById("btn-nav-back");
  if (backBtn) backBtn.classList.toggle("hidden", state.navHistory.length === 0);
  document.querySelectorAll(".sidebar .nav-link[data-view]").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.view === viewName);
  });
  renderSidebarPlaylists();
  renderMainWorkspace();
}

function navigateBack() {
  const prev = state.navHistory.pop();
  if (!prev) {
    navigateToView("home", false);
    return;
  }
  state.selectedArtistName = prev.selectedArtistName;
  state.drilldownTitle = prev.drilldownTitle;
  state.drilldownTracks = prev.drilldownTracks;
  state.activePlaylistId = prev.activePlaylistId;
  navigateToView(prev.view, false);
}

function openAlbumDrilldown(albumName, albumTracks) {
  state.drilldownTitle = albumName;
  state.drilldownTracks = albumTracks;
  navigateToView("drilldown", true);
}

function openArtistDetailPage(artistName) {
  state.selectedArtistName = artistName;
  navigateToView("artist-detail", true);
}

function getFilteredTracks(list) {
  const q = state.searchQuery.trim().toLowerCase();
  let out = q
    ? list.filter(
        (t) =>
          t.title.toLowerCase().includes(q) ||
          t.artist.toLowerCase().includes(q) ||
          t.album.toLowerCase().includes(q) ||
          t.format.toLowerCase().includes(q)
      )
    : [...list];

  if (state.sortMode === "title") {
    out.sort((a, b) => a.title.localeCompare(b.title));
  } else if (state.sortMode === "quality") {
    out.sort((a, b) => b.sampleRateHz * b.bitDepth - a.sampleRateHz * a.bitDepth);
  } else if (state.sortMode === "duration") {
    out.sort((a, b) => b.durationMs - a.durationMs);
  }
  return out;
}

function renderMainWorkspace() {
  const view = document.getElementById("main-view");
  view.innerHTML = "";
  view.classList.remove("view-animated");
  void view.offsetWidth;
  view.classList.add("view-animated");

  const visTracks = getVisibleTracks();

  if (state.currentView === "home") {
    renderHome(view);
  } else if (state.currentView === "tracks") {
    renderTrackTable(view, "Треки", visTracks);
  } else if (state.currentView === "history") {
    const historyTracks = (state.recentlyPlayedIds || [])
      .map((id) => visTracks.find((t) => t.id === id))
      .filter(Boolean);
    renderTrackTable(view, "История прослушивания", historyTracks, null, true, {
      preserveOrder: true,
      subtitle: `${historyTracks.length} недавно прослушанных треков`,
      onClearHistory: async () => {
        state.recentlyPlayedIds = await api.clearRecentlyPlayed();
        renderMainWorkspace();
      },
    });
  } else if (state.currentView === "recent-added") {
    const recentAddedTracks = [...visTracks].sort(
      (a, b) => (b.dateAdded || 0) - (a.dateAdded || 0)
    );
    renderTrackTable(view, "Недавно добавленные", recentAddedTracks, null, true, {
      preserveOrder: true,
      subtitle: `${recentAddedTracks.length} треков по дате добавления`,
    });
  } else if (state.currentView === "favorites") {
    const favSet = new Set(state.favoriteIds);
    renderTrackTable(
      view,
      "Избранное",
      visTracks.filter((t) => favSet.has(t.id))
    );
  } else if (state.currentView === "playlist") {
    const pl = state.playlists.find((p) => p.id === state.activePlaylistId);
    if (!pl) return navigateToView("tracks", false);
    const idSet = new Set(pl.trackIds);
    renderTrackTable(
      view,
      pl.name,
      visTracks.filter((t) => idSet.has(t.id)),
      pl.id
    );
  } else if (state.currentView === "albums") {
    renderAlbumsGrid(view);
  } else if (state.currentView === "artists") {
    renderArtistsGrid(view);
  } else if (state.currentView === "artist-detail") {
    renderArtistDetailPage(view, state.selectedArtistName);
  } else if (state.currentView === "drilldown") {
    renderTrackTable(view, state.drilldownTitle, state.drilldownTracks, null, true);
  } else if (state.currentView === "equalizer") {
    renderEqualizer(view);
  } else if (state.currentView === "settings") {
    renderSettings(view);
  } else {
    renderHome(view);
  }
}

/* DRAG-TO-SCROLL WITH MOMENTUM FOR HORIZONTAL CAROUSELS */
function enableHorizontalDragScroll(scrollEl) {
  let isDown = false;
  let startX = 0;
  let scrollLeftStart = 0;
  let didDrag = false;
  let lastX = 0;
  let velocity = 0;
  let rafId = null;

  const stopMomentum = () => {
    if (rafId) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
  };

  scrollEl.addEventListener("mousedown", (e) => {
    if (e.button !== 0) return;
    stopMomentum();
    isDown = true;
    didDrag = false;
    startX = e.pageX;
    lastX = e.pageX;
    velocity = 0;
    scrollLeftStart = scrollEl.scrollLeft;
  });

  window.addEventListener("mousemove", (e) => {
    if (!isDown) return;
    const dx = e.pageX - startX;
    if (!didDrag && Math.abs(dx) > 6) {
      didDrag = true;
      scrollEl.classList.add("dragging");
    }
    if (didDrag) {
      velocity = e.pageX - lastX;
      lastX = e.pageX;
      scrollEl.scrollLeft = scrollLeftStart - dx;
    }
  });

  window.addEventListener("mouseup", () => {
    if (!isDown) return;
    isDown = false;
    scrollEl.classList.remove("dragging");
    let v = velocity * 1.15;
    if (didDrag && Math.abs(v) > 1.5) {
      const glide = () => {
        scrollEl.scrollLeft -= v;
        v *= 0.92;
        if (Math.abs(v) > 0.4) {
          rafId = requestAnimationFrame(glide);
        }
      };
      rafId = requestAnimationFrame(glide);
    }
    setTimeout(() => {
      didDrag = false;
    }, 50);
  });

  // Block click event on cards right after a drag gesture
  scrollEl.addEventListener(
    "click",
    (e) => {
      if (didDrag) {
        e.preventDefault();
        e.stopPropagation();
        didDrag = false;
      }
    },
    true
  );

  // Horizontal touchpad / Shift+wheel works seamlessly; also allow wheel horizontally when no vertical scroll needed
  scrollEl.addEventListener(
    "wheel",
    (e) => {
      if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
      if (e.shiftKey) {
        e.preventDefault();
        scrollEl.scrollLeft += e.deltaY;
      }
    },
    { passive: false }
  );
}

/* MOBILE-CLONED HOME DASHBOARD (ОБЗОР) */
function renderHome(container) {
  const visTracks = getVisibleTracks();
  if (visTracks.length === 0) {
    container.innerHTML = `
      <div class="mono-card" style="max-width:480px;margin:70px auto;text-align:center;padding:38px;">
        <div style="font-size:18px;font-weight:800;">Медиатека пуста</div>
        <p style="color:var(--fg-2);font-size:13px;line-height:1.6;margin:10px 0 22px;">
          Выберите папку с вашей музыкой (FLAC, WAV, MP3, OGG, M4A), чтобы начать воспроизведение.
        </p>
        <button id="btn-empty-add" class="mono-btn solid">Добавить папку</button>
      </div>
    `;
    document.getElementById("btn-empty-add").onclick = handlePickFolder;
    return;
  }

  const albumsMap = new Map();
  const artistsMap = new Map();
  visTracks.forEach((t) => {
    if (!albumsMap.has(t.album)) albumsMap.set(t.album, []);
    albumsMap.get(t.album).push(t);
    if (!artistsMap.has(t.artist)) artistsMap.set(t.artist, []);
    artistsMap.get(t.artist).push(t);
  });

  const albumsEntries = Array.from(albumsMap.entries());
  const artistsEntries = Array.from(artistsMap.entries());

  // 1. Top 3 Quick Action Cards: Микс треков | Случайный альбом | Случайный исполнитель
  const chipsRow = document.createElement("div");
  chipsRow.className = "mix-chips-row";
  chipsRow.innerHTML = `
    <div id="chip-mix-tracks" class="mix-chip-card">
      <div class="mix-chip-icon">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.1">
          <polyline points="16 3 21 3 21 8"></polyline>
          <line x1="4" y1="20" x2="21" y2="3"></line>
          <polyline points="21 16 21 21 16 21"></polyline>
          <line x1="15" y1="15" x2="21" y2="21"></line>
          <line x1="4" y1="4" x2="9" y2="9"></line>
        </svg>
      </div>
      <div>
        <div class="mix-chip-title">Микс треков</div>
        <div class="mix-chip-sub">${visTracks.length} песен</div>
      </div>
    </div>

    <div id="chip-random-album" class="mix-chip-card">
      <div class="mix-chip-icon">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.1">
          <circle cx="12" cy="12" r="10"></circle>
          <circle cx="12" cy="12" r="3"></circle>
        </svg>
      </div>
      <div>
        <div class="mix-chip-title">Случайный альбом</div>
        <div class="mix-chip-sub">${albumsEntries.length} альбомов</div>
      </div>
    </div>

    <div id="chip-random-artist" class="mix-chip-card">
      <div class="mix-chip-icon">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.1">
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
          <circle cx="12" cy="7" r="4"></circle>
        </svg>
      </div>
      <div>
        <div class="mix-chip-title">Случайный исполнитель</div>
        <div class="mix-chip-sub">${artistsEntries.length} артистов</div>
      </div>
    </div>
  `;
  container.appendChild(chipsRow);

  document.getElementById("chip-mix-tracks").onclick = () => {
    if (!visTracks.length) return;
    state.isShuffle = true;
    updateTransportState();
    const seed = visTracks[Math.floor(Math.random() * visTracks.length)];
    playTrackWithQueue(seed, visTracks);
  };
  document.getElementById("chip-random-album").onclick = () => {
    if (albumsEntries.length) {
      const [name, list] = albumsEntries[Math.floor(Math.random() * albumsEntries.length)];
      openAlbumDrilldown(name, list);
    }
  };
  document.getElementById("chip-random-artist").onclick = () => {
    if (artistsEntries.length) {
      const [artistName] = artistsEntries[Math.floor(Math.random() * artistsEntries.length)];
      openArtistDetailPage(artistName);
    }
  };

  // Helper to create a horizontal carousel section with drag-to-scroll & arrow controls
  const appendCarouselSection = (title, countText, onHeaderClick, itemsBuilder) => {
    const sec = document.createElement("div");
    sec.className = "dash-section";
    sec.innerHTML = `
      <div class="dash-header">
        <div class="dash-title-wrap">
          <span class="dash-title">${escapeHtml(title)}</span>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="9 18 15 12 9 6"></polyline>
          </svg>
        </div>
        <div class="dash-header-right">
          <div class="carousel-arrows">
            <button class="carousel-arrow-btn" data-dir="-1" title="Прокрутить влево">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2">
                <polyline points="15 18 9 12 15 6"></polyline>
              </svg>
            </button>
            <button class="carousel-arrow-btn" data-dir="1" title="Прокрутить вправо">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2">
                <polyline points="9 18 15 12 9 6"></polyline>
              </svg>
            </button>
          </div>
          <span class="dash-count">${escapeHtml(String(countText))}</span>
        </div>
      </div>
      <div class="h-scroll-row"></div>
    `;
    const headerEl = sec.querySelector(".dash-header");
    if (onHeaderClick) {
      headerEl.onclick = (e) => {
        if (e.target.closest(".carousel-arrows")) return;
        onHeaderClick();
      };
    }
    const row = sec.querySelector(".h-scroll-row");
    sec.querySelectorAll(".carousel-arrow-btn").forEach((btn) => {
      btn.onclick = (e) => {
        e.stopPropagation();
        const dir = parseInt(btn.dataset.dir, 10) || 1;
        row.scrollBy({ left: dir * Math.max(280, row.clientWidth * 0.72), behavior: "smooth" });
      };
    });
    itemsBuilder(row);
    enableHorizontalDragScroll(row);
    container.appendChild(sec);
  };

  // 2. История прослушивания (if non-empty)
  const allRecentPlayed = (state.recentlyPlayedIds || [])
    .map((id) => visTracks.find((t) => t.id === id))
    .filter(Boolean);
  const recentPlayedTracks = allRecentPlayed.slice(0, 15);

  if (recentPlayedTracks.length > 0) {
    appendCarouselSection(
      "История прослушивания",
      allRecentPlayed.length,
      () => navigateToView("history", true),
      (row) => {
        recentPlayedTracks.forEach((tr) => {
          row.appendChild(
            createHorizontalCard(tr.title, tr.artist, tr, () =>
              playTrackWithQueue(tr, allRecentPlayed)
            )
          );
        });
      }
    );
  }

  // 3. Недавно добавленные
  const allRecentlyAdded = [...visTracks].sort(
    (a, b) => (b.dateAdded || 0) - (a.dateAdded || 0)
  );
  const recentlyAdded = allRecentlyAdded.slice(0, 18);

  appendCarouselSection(
    "Недавно добавленные",
    allRecentlyAdded.length,
    () => navigateToView("recent-added", true),
    (row) => {
      recentlyAdded.forEach((tr) => {
        row.appendChild(
          createHorizontalCard(tr.title, tr.artist, tr, () =>
            playTrackWithQueue(tr, allRecentlyAdded)
          )
        );
      });
    }
  );

  // 4. Исполнители (Regular rounded-rectangle cards like all other cards!)
  appendCarouselSection(
    "Исполнители",
    artistsEntries.length,
    () => navigateToView("artists", true),
    (row) => {
      artistsEntries.slice(0, 16).forEach(([artistName, list]) => {
        const sample = list.find((t) => getEffectiveTrackCover(t)) || list[0];
        row.appendChild(
          createHorizontalCard(
            artistName,
            `${list.length} треков`,
            sample,
            () => openArtistDetailPage(artistName)
          )
        );
      });
    }
  );

  // 5. Альбомы
  appendCarouselSection(
    "Альбомы",
    albumsEntries.length,
    () => navigateToView("albums", true),
    (row) => {
      albumsEntries.slice(0, 16).forEach(([albumName, list]) => {
        const sample = list.find((t) => getEffectiveTrackCover(t)) || list[0];
        row.appendChild(
          createHorizontalCard(
            albumName,
            sample?.artist || "",
            sample,
            () => openAlbumDrilldown(albumName, list)
          )
        );
      });
    }
  );
}

function createHorizontalCard(title, subtitle, sampleTrack, onClick) {
  const coverPath = typeof sampleTrack === "string" ? sampleTrack : getEffectiveTrackCover(sampleTrack);
  const card = document.createElement("div");
  card.className = "h-card";
  if (sampleTrack && typeof sampleTrack === "object" && sampleTrack.id) {
    card.dataset.coverTrackId = sampleTrack.id;
    requestOnlineCoverForTrack(sampleTrack);
  }
  const artHtml = coverPath
    ? `<img src="${toFileUrl(coverPath)}" loading="lazy" decoding="async" draggable="false" alt="" />`
    : `<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"></circle><circle cx="12" cy="12" r="3"></circle></svg>`;
  card.innerHTML = `
    <div class="h-card-art">${artHtml}</div>
    <div class="grid-title">${escapeHtml(title)}</div>
    <div class="grid-sub">${escapeHtml(subtitle)}</div>
  `;
  card.onclick = onClick;
  return card;
}

function buildMediaCard(title, subtitle, tracks, onClick) {
  const sample = tracks.find((t) => getEffectiveTrackCover(t)) || tracks[0];
  const imgSrc = getEffectiveTrackCover(sample);
  const card = document.createElement("div");
  card.className = "grid-card";
  if (sample && sample.id) {
    card.dataset.coverTrackId = sample.id;
    requestOnlineCoverForTrack(sample);
  }
  const coverHtml = imgSrc
    ? `<img src="${toFileUrl(imgSrc)}" loading="lazy" decoding="async" draggable="false" alt="" />`
    : `<svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="12" cy="12" r="10"></circle><circle cx="12" cy="12" r="3"></circle></svg>`;

  card.innerHTML = `
    <div class="grid-art">${coverHtml}</div>
    <div class="grid-title">${escapeHtml(title)}</div>
    <div class="grid-sub">${escapeHtml(subtitle)}</div>
  `;
  card.onclick = onClick;
  return card;
}

function renderAlbumsGrid(container) {
  const albumsMap = new Map();
  getFilteredTracks(getVisibleTracks()).forEach((t) => {
    if (!albumsMap.has(t.album)) albumsMap.set(t.album, []);
    albumsMap.get(t.album).push(t);
  });

  container.innerHTML = `
    <div class="view-header">
      <div>
        <div class="view-title">Альбомы</div>
        <div class="view-subtitle">${albumsMap.size} альбомов</div>
      </div>
    </div>
  `;
  const grid = document.createElement("div");
  grid.className = "media-grid";
  albumsMap.forEach((tracks, name) => {
    grid.appendChild(
      buildMediaCard(name, `${tracks[0]?.artist || ""} • ${tracks.length}`, tracks, () =>
        openAlbumDrilldown(name, tracks)
      )
    );
  });
  container.appendChild(grid);
}

function renderArtistsGrid(container) {
  const artistsMap = new Map();
  getFilteredTracks(getVisibleTracks()).forEach((t) => {
    if (!artistsMap.has(t.artist)) artistsMap.set(t.artist, []);
    artistsMap.get(t.artist).push(t);
  });

  container.innerHTML = `
    <div class="view-header">
      <div>
        <div class="view-title">Исполнители</div>
        <div class="view-subtitle">${artistsMap.size} исполнителей</div>
      </div>
    </div>
  `;
  const grid = document.createElement("div");
  grid.className = "media-grid";
  artistsMap.forEach((tracks, artist) => {
    const albumsSet = new Set(tracks.map((t) => t.album));
    grid.appendChild(
      buildMediaCard(
        artist,
        `${albumsSet.size} альб. • ${tracks.length} треков`,
        tracks,
        () => openArtistDetailPage(artist)
      )
    );
  });
  container.appendChild(grid);
}

/* DEDICATED ARTIST DETAIL PAGE (SHOWS ALL ALBUMS BY ARTIST + ALL TRACKS) */
function renderArtistDetailPage(container, artistName) {
  const artistTracks = getVisibleTracks().filter(
    (t) => t.artist.toLowerCase() === (artistName || "").toLowerCase()
  );
  const albumsMap = new Map();
  artistTracks.forEach((t) => {
    if (!albumsMap.has(t.album)) albumsMap.set(t.album, []);
    albumsMap.get(t.album).push(t);
  });

  const topHeader = document.createElement("div");
  topHeader.className = "view-header";
  topHeader.innerHTML = `
    <div style="display:flex;align-items:center;gap:12px;">
      <button id="btn-artist-back" class="mono-btn">← Назад</button>
      <div>
        <div class="view-title">${escapeHtml(artistName)}</div>
        <div class="view-subtitle">${albumsMap.size} альбомов • ${artistTracks.length} треков</div>
      </div>
    </div>
    <div class="view-actions">
      <button id="btn-artist-shuffle" class="mono-btn">Перемешать</button>
      <button id="btn-artist-play-all" class="mono-btn solid">Играть всё</button>
    </div>
  `;
  container.appendChild(topHeader);

  document.getElementById("btn-artist-back").onclick = navigateBack;
  document.getElementById("btn-artist-play-all").onclick = () => {
    if (artistTracks.length) playTrackWithQueue(artistTracks[0], artistTracks);
  };
  document.getElementById("btn-artist-shuffle").onclick = () => {
    if (!artistTracks.length) return;
    state.isShuffle = true;
    updateTransportState();
    const seed = artistTracks[Math.floor(Math.random() * artistTracks.length)];
    playTrackWithQueue(seed, artistTracks);
  };

  // Section 1: Albums by this artist
  const albTitle = document.createElement("div");
  albTitle.className = "dash-title";
  albTitle.style.margin = "8px 0 12px";
  albTitle.textContent = "Альбомы";
  container.appendChild(albTitle);

  const grid = document.createElement("div");
  grid.className = "media-grid";
  grid.style.marginBottom = "26px";
  albumsMap.forEach((tracks, albumName) => {
    grid.appendChild(
      buildMediaCard(albumName, `${tracks.length} треков`, tracks, () =>
        openAlbumDrilldown(albumName, tracks)
      )
    );
  });
  container.appendChild(grid);

  // Section 2: All tracks by this artist
  const tracksContainer = document.createElement("div");
  renderTrackTable(tracksContainer, "Все треки исполнителя", artistTracks, null, false);
  container.appendChild(tracksContainer);
}

/* FLOATING PLAYLIST PICKER MENU */
function openPlaylistPopover(anchorBtn, trackId) {
  const pop = document.getElementById("playlist-popover");
  document.getElementById("track-menu-popover")?.classList.add("hidden");
  pop.innerHTML = `<div class="popover-title">ДОБАВИТЬ В ПЛЕЙЛИСТ</div>`;

  state.playlists.forEach((pl) => {
    const item = document.createElement("button");
    item.className = "popover-item";
    const alreadyIn = pl.trackIds.includes(trackId);
    item.innerHTML = `
      <span>${escapeHtml(pl.name)}</span>
      <span style="font-size:11px;opacity:0.65;">${alreadyIn ? "✓" : pl.trackIds.length}</span>
    `;
    item.onclick = async (e) => {
      e.stopPropagation();
      state.playlists = await api.addTrackToPlaylist(pl.id, trackId);
      renderSidebarPlaylists();
      pop.classList.add("hidden");
      showToast(`Добавлено в «${pl.name}»`);
    };
    pop.appendChild(item);
  });

  const createBtn = document.createElement("button");
  createBtn.className = "popover-item";
  createBtn.style.borderTop = "1px solid var(--line)";
  createBtn.style.marginTop = "4px";
  createBtn.style.paddingTop = "8px";
  createBtn.innerHTML = `<span>+ Новый плейлист...</span>`;
  createBtn.onclick = (e) => {
    e.stopPropagation();
    pop.classList.add("hidden");
    state.pendingTrackForNewPlaylist = trackId;
    const plModal = document.getElementById("playlist-modal");
    const plInput = document.getElementById("playlist-name-input");
    plInput.value = "";
    plModal.classList.remove("hidden");
    plInput.focus();
  };
  pop.appendChild(createBtn);

  pop.classList.remove("hidden");
  const rect = anchorBtn.getBoundingClientRect();
  const top = Math.min(window.innerHeight - pop.offsetHeight - 16, rect.bottom + 6);
  const left = Math.max(16, Math.min(window.innerWidth - pop.offsetWidth - 16, rect.right - pop.offsetWidth));
  pop.style.top = `${top}px`;
  pop.style.left = `${left}px`;
}

function openTrackInfoModal(track) {
  if (!track) return;
  const modal = document.getElementById("track-info-modal");
  const body = document.getElementById("track-info-body");
  const rows = [
    ["Название", track.title || "—"],
    ["Исполнитель", track.artist || "—"],
    ["Альбом", track.album || "—"],
    ["Формат", `${track.format || "AUDIO"} • ${track.bitDepth || 16}-bit`],
    ["Дискретизация", `${((track.sampleRateHz || 44100) / 1000).toFixed(1)} kHz (${track.channels || 2} ch)`],
    ["Битрейт", track.bitrateKbps > 0 ? `${track.bitrateKbps} kbps` : "Lossless / VBR"],
    ["Длительность", formatTime(track.durationMs)],
    ["Движок", isNativeTrackSupported(track) ? "Native C++ (miniaudio)" : "Chromium WebAudio"],
    ["Файл", track.path || "—"],
  ];
  body.innerHTML = rows
    .map(
      ([k, v]) =>
        `<div class="info-kv-row"><span class="info-kv-k">${escapeHtml(k)}</span><span class="info-kv-v">${escapeHtml(String(v))}</span></div>`
    )
    .join("");
  modal.classList.remove("hidden");
}

function openTrackMenuPopover(anchorBtn, track, playlistId = null) {
  const pop = document.getElementById("track-menu-popover");
  document.getElementById("playlist-popover")?.classList.add("hidden");
  pop.innerHTML = `<div class="popover-title">${escapeHtml(track.title)}</div>`;

  const addAction = (label, onClick) => {
    const btn = document.createElement("button");
    btn.className = "popover-item";
    btn.innerHTML = `<span>${escapeHtml(label)}</span>`;
    btn.onclick = async (e) => {
      e.stopPropagation();
      pop.classList.add("hidden");
      await onClick();
    };
    pop.appendChild(btn);
  };

  addAction("Играть следующим", () => {
    if (!state.queue.length) {
      playTrackWithQueue(track, [track]);
      return;
    }
    const filtered = state.queue.filter((x) => x.id !== track.id);
    const curIdx = state.currentTrack ? filtered.findIndex((x) => x.id === state.currentTrack.id) : -1;
    filtered.splice(curIdx + 1, 0, track);
    state.queue = filtered;
    if (curIdx >= 0) state.currentIndex = curIdx;
    renderQueueDock();
    showToast("Добавлено следующим в очередь");
  });

  addAction("Добавить в конец очереди", () => {
    if (!state.queue.length) {
      playTrackWithQueue(track, [track]);
      return;
    }
    state.queue.push(track);
    renderQueueDock();
    showToast("Добавлено в очередь");
  });

  addAction("Добавить в плейлист...", () => {
    openPlaylistPopover(anchorBtn, track.id);
  });

  if (playlistId) {
    addAction("Убрать из плейлиста", async () => {
      state.playlists = await api.removeTrackFromPlaylist(playlistId, track.id);
      renderSidebarPlaylists();
      renderMainWorkspace();
      showToast("Трек убран из плейлиста");
    });
  }

  addAction("Свойства файла", () => {
    openTrackInfoModal(track);
  });

  addAction("Скрыть трек из медиатеки", () => {
    const hidden = new Set(state.settings.hiddenTrackIds || []);
    hidden.add(track.id);
    state.settings.hiddenTrackIds = Array.from(hidden);
    api.saveSettings(state.settings);
    updateCountsAndBadges();
    renderMainWorkspace();
    showToast("Трек скрыт (можно вернуть в Настройках)");
  });

  pop.classList.remove("hidden");
  const rect = anchorBtn.getBoundingClientRect();
  const top = Math.min(window.innerHeight - pop.offsetHeight - 16, rect.bottom + 6);
  const left = Math.max(16, Math.min(window.innerWidth - pop.offsetWidth - 16, rect.right - pop.offsetWidth));
  pop.style.top = `${top}px`;
  pop.style.left = `${left}px`;
}

function renderTrackTable(
  container,
  title,
  sourceTracks,
  playlistId = null,
  isDrilldown = false,
  options = {}
) {
  let filtered;
  if (options.preserveOrder) {
    const q = state.searchQuery.trim().toLowerCase();
    filtered = q
      ? sourceTracks.filter(
          (t) =>
            t.title.toLowerCase().includes(q) ||
            t.artist.toLowerCase().includes(q) ||
            t.album.toLowerCase().includes(q) ||
            t.format.toLowerCase().includes(q)
        )
      : [...sourceTracks];
  } else {
    filtered = getFilteredTracks(sourceTracks);
  }
  const visibleSlice = filtered.slice(0, state.trackListLimit);

  const header = document.createElement("div");
  header.className = "view-header";
  header.innerHTML = `
    <div style="display:flex;align-items:center;gap:12px;">
      ${isDrilldown ? `<button id="btn-back-drill" class="mono-btn">← Назад</button>` : ""}
      <div>
        <div class="view-title">${escapeHtml(title)}</div>
        <div class="view-subtitle">${escapeHtml(options.subtitle || `${filtered.length} треков`)}</div>
      </div>
    </div>
    <div class="view-actions">
      ${
        options.preserveOrder
          ? ""
          : `<select id="select-sort" class="mono-select">
        <option value="artist" ${state.sortMode === "artist" ? "selected" : ""}>По альбому / исполнителю</option>
        <option value="title" ${state.sortMode === "title" ? "selected" : ""}>По названию (A–Z)</option>
        <option value="quality" ${state.sortMode === "quality" ? "selected" : ""}>По качеству</option>
        <option value="duration" ${state.sortMode === "duration" ? "selected" : ""}>По длительности</option>
      </select>`
      }
      ${options.onClearHistory && filtered.length ? `<button id="btn-clear-hist" class="mono-btn">Очистить историю</button>` : ""}
      ${state.currentView === "tracks" && !isDrilldown ? `<button id="btn-rescan-list" class="mono-btn" title="Пересканировать папки с музыкой">Пересканировать</button>` : ""}
      <button id="btn-shuffle-list" class="mono-btn">Перемешать</button>
      <button id="btn-play-list" class="mono-btn solid">Играть всё</button>
      ${playlistId ? `<button id="btn-del-pl" class="mono-btn">Удалить плейлист</button>` : ""}
    </div>
  `;
  container.appendChild(header);

  if (isDrilldown) {
    document.getElementById("btn-back-drill").onclick = navigateBack;
  }
  const sortSel = header.querySelector("#select-sort");
  if (sortSel) {
    sortSel.onchange = (e) => {
      state.sortMode = e.target.value;
      renderMainWorkspace();
    };
  }
  const clearHistBtn = header.querySelector("#btn-clear-hist");
  if (clearHistBtn && options.onClearHistory) {
    clearHistBtn.onclick = options.onClearHistory;
  }
  const rescanListBtn = header.querySelector("#btn-rescan-list");
  if (rescanListBtn) {
    rescanListBtn.onclick = handleRescanLibrary;
  }
  header.querySelector("#btn-shuffle-list").onclick = () => {
    if (!filtered.length) return;
    state.isShuffle = true;
    updateTransportState();
    const seed = filtered[Math.floor(Math.random() * filtered.length)];
    playTrackWithQueue(seed, filtered);
  };
  header.querySelector("#btn-play-list").onclick = () => {
    if (filtered.length) playTrackWithQueue(filtered[0], filtered);
  };
  if (playlistId) {
    header.querySelector("#btn-del-pl").onclick = async () => {
      state.playlists = await api.deletePlaylist(playlistId);
      navigateToView("tracks", false);
    };
  }

  const listBox = document.createElement("div");
  listBox.className = `tracks-table ${state.settings.compactMode ? "compact" : ""}`;
  const favSet = new Set(state.favoriteIds);
  const fragment = document.createDocumentFragment();

  visibleSlice.forEach((t, idx) => {
    const row = document.createElement("div");
    const isCurrent = state.currentTrack && state.currentTrack.id === t.id;
    row.className = `track-item ${isCurrent ? "active" : ""}`;
    row.dataset.trackId = t.id;

    const num = t.trackNumber > 0 ? t.trackNumber : idx + 1;
    const isFav = favSet.has(t.id);
    const thumb = getEffectiveTrackCover(t, false);
    if (!thumb) requestOnlineCoverForTrack(t);
    const artHtml = thumb
      ? `<img src="${toFileUrl(thumb)}" loading="lazy" decoding="async" alt="" />`
      : `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>`;

    row.innerHTML = `
      ${
        state.settings.showTrackNumbers
          ? `<div class="track-index">${String(num).padStart(2, "0")}</div>`
          : ""
      }
      <div class="track-art">${artHtml}</div>
      <div class="track-info">
        <div class="track-title">${escapeHtml(t.title)}</div>
        <div class="track-sub">${escapeHtml(t.artist)} • ${escapeHtml(t.album)}</div>
      </div>
      ${
        state.settings.showQualityBadges
          ? `<span class="format-tag">${escapeHtml(formatTrackBadge(t))}</span>`
          : ""
      }
      <div class="track-time">${formatTime(t.durationMs)}</div>
      <button class="icon-btn" data-act="fav" title="Избранное">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="${isFav ? "currentColor" : "none"}" stroke="currentColor" stroke-width="2">
          <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"></path>
        </svg>
      </button>
      <button class="icon-btn" data-act="menu" title="Действия">⋮</button>
    `;

    row.onclick = async (e) => {
      const actBtn = e.target.closest("[data-act]");
      if (actBtn) {
        e.stopPropagation();
        if (actBtn.dataset.act === "fav") {
          state.favoriteIds = await api.toggleFavorite(t.id);
          updateCountsAndBadges();
          updateBarNowPlaying();
          renderMainWorkspace();
        } else if (actBtn.dataset.act === "pl") {
          openPlaylistPopover(actBtn, t.id);
        } else if (actBtn.dataset.act === "menu") {
          openTrackMenuPopover(actBtn, t, playlistId);
        }
        return;
      }
      playTrackWithQueue(t, filtered);
    };

    fragment.appendChild(row);
  });

  listBox.appendChild(fragment);
  container.appendChild(listBox);

  if (filtered.length > state.trackListLimit) {
    const moreBtn = document.createElement("button");
    moreBtn.className = "mono-btn full";
    moreBtn.style.marginTop = "12px";
    moreBtn.textContent = `Показать ещё (${filtered.length - state.trackListLimit} треков)`;
    moreBtn.onclick = () => {
      state.trackListLimit += 250;
      renderMainWorkspace();
    };
    container.appendChild(moreBtn);
  }
}

/* EQUALIZER WITH SEPARATED PREAMP/SUB-BASS + INTERACTIVE SPLINE CURVE */
function drawEqResponseCurve() {
  const canvas = document.getElementById("eq-curve-canvas");
  if (!canvas) return;
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth || 500;
  const h = canvas.clientHeight || 116;
  if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
    canvas.width = w * dpr;
    canvas.height = h * dpr;
  }
  const ctx = canvas.getContext("2d");
  ctx.save();
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);

  const styles = getComputedStyle(document.documentElement);
  ctx.strokeStyle = styles.getPropertyValue("--line").trim() || "rgba(128,128,128,0.2)";
  ctx.lineWidth = 1;
  [
    [0.2, "+12 dB"],
    [0.5, "0 dB"],
    [0.8, "-12 dB"],
  ].forEach(([ry, lbl]) => {
    ctx.beginPath();
    ctx.moveTo(0, h * ry);
    ctx.lineTo(w, h * ry);
    ctx.stroke();
    ctx.fillStyle = styles.getPropertyValue("--fg-2").trim() || "#888";
    ctx.font = "10px sans-serif";
    ctx.fillText(lbl, 6, h * ry - 3);
  });

  const gains = state.eqConfig.bandGains;
  const enabled = state.eqConfig.enabled;
  const pts = gains.map((g, i) => {
    const x = (w / 10) * (i + 0.5);
    const norm = enabled ? Math.max(-12, Math.min(12, g)) / 12 : 0;
    const y = h * 0.5 - norm * (h * 0.36);
    return { x, y };
  });

  ctx.beginPath();
  ctx.moveTo(0, h * 0.5);
  ctx.lineTo(pts[0].x, pts[0].y);
  for (let i = 0; i < pts.length - 1; i++) {
    const xc = (pts[i].x + pts[i + 1].x) / 2;
    const yc = (pts[i].y + pts[i + 1].y) / 2;
    ctx.quadraticCurveTo(pts[i].x, pts[i].y, xc, yc);
  }
  ctx.lineTo(pts[pts.length - 1].x, pts[pts.length - 1].y);
  ctx.lineTo(w, h * 0.5);
  ctx.closePath();
  const accRgb = hexToRgb(getAccentCssColor());
  ctx.fillStyle = enabled
    ? `rgba(${accRgb.r}, ${accRgb.g}, ${accRgb.b}, 0.14)`
    : "rgba(255, 255, 255, 0.03)";
  ctx.fill();

  ctx.beginPath();
  ctx.moveTo(pts[0].x, pts[0].y);
  for (let i = 0; i < pts.length - 1; i++) {
    const xc = (pts[i].x + pts[i + 1].x) / 2;
    const yc = (pts[i].y + pts[i + 1].y) / 2;
    ctx.quadraticCurveTo(pts[i].x, pts[i].y, xc, yc);
  }
  ctx.lineTo(pts[pts.length - 1].x, pts[pts.length - 1].y);
  ctx.strokeStyle = enabled ? getAccentCssColor() : "#555555";
  ctx.lineWidth = 2.2;
  ctx.stroke();

  pts.forEach((p) => {
    ctx.beginPath();
    ctx.arc(p.x, p.y, 3.6, 0, Math.PI * 2);
    ctx.fillStyle = enabled ? getAccentCssColor() : "#666666";
    ctx.fill();
  });

  ctx.restore();
}

function renderEqualizer(container) {
  const cfg = state.eqConfig;
  const presetOpts = Object.keys(EQ_PRESETS)
    .map(
      (p) => `<option value="${p}" ${cfg.presetName === p ? "selected" : ""}>${p}</option>`
    )
    .join("");

  container.innerHTML = `
    <div class="view-header">
      <div>
        <div class="view-title">Эквалайзер</div>
      </div>
      <div class="view-actions">
        <select id="eq-preset-sel" class="mono-select">
          ${presetOpts}
          <option value="Custom" ${!EQ_PRESETS[cfg.presetName] ? "selected" : ""}>Custom</option>
        </select>
        <button id="btn-eq-zero" class="mono-btn">Сбросить</button>
        <button id="btn-eq-power" class="mono-btn ${cfg.enabled ? "solid" : ""}">
          ${cfg.enabled ? "Включён" : "Выключен"}
        </button>
      </div>
    </div>

    <div class="eq-layout">
      <div class="mono-card eq-tone-card">
        <div class="tone-block">
          <div class="tone-header">
            <span>Усиление (Preamp)</span>
            <span id="val-preamp" class="eq-val">${cfg.preampDb > 0 ? "+" : ""}${cfg.preampDb.toFixed(1)} dB</span>
          </div>
          <input id="sl-preamp" type="range" min="-12" max="12" step="0.5" value="${cfg.preampDb}" class="mobile-slider" />
          <div class="view-subtitle">Входной уровень перед фильтрами</div>
        </div>

        <div class="tone-block">
          <div class="tone-header">
            <span>Саб-бас (80 Hz)</span>
            <span id="val-bass" class="eq-val">+${(cfg.bassBoost * 12).toFixed(1)} dB</span>
          </div>
          <input id="sl-bass" type="range" min="0" max="1" step="0.05" value="${cfg.bassBoost}" class="mobile-slider" />
          <div class="view-subtitle">Низкочастотный шельф-фильтр</div>
        </div>
      </div>

      <div class="mono-card">
        <canvas id="eq-curve-canvas" class="eq-curve-canvas"></canvas>
        <div class="eq-bands-grid">
          ${cfg.bandGains
            .map(
              (g, i) => `
            <div class="eq-col">
              <span id="val-b-${i}" class="eq-val">${g > 0 ? "+" : ""}${g.toFixed(1)}</span>
              <div class="eq-v-wrap">
                <input data-band="${i}" type="range" min="-12" max="12" step="0.5" value="${g}" class="eq-slider-v sl-band" />
              </div>
              <span class="eq-hz">${EQ_LABELS[i]}</span>
            </div>
          `
            )
            .join("")}
        </div>
      </div>
    </div>
  `;

  const slPreamp = document.getElementById("sl-preamp");
  const slBass = document.getElementById("sl-bass");
  updateSliderFill(slPreamp);
  updateSliderFill(slBass);
  container.querySelectorAll(".sl-band").forEach(updateSliderFill);

  requestAnimationFrame(drawEqResponseCurve);

  let eqSaveTimer = null;
  const persistEq = (debounced = false) => {
    applyEqToAudioGraph();
    if (state.nativeAvailable) api.nativeUpdateEq(state.eqConfig);
    updateCountsAndBadges();
    drawEqResponseCurve();
    if (debounced) {
      clearTimeout(eqSaveTimer);
      eqSaveTimer = setTimeout(() => api.saveEqConfig(state.eqConfig), 180);
    } else {
      clearTimeout(eqSaveTimer);
      api.saveEqConfig(state.eqConfig);
    }
  };

  document.getElementById("btn-eq-power").onclick = () => {
    state.eqConfig.enabled = !state.eqConfig.enabled;
    persistEq();
    renderEqualizer(container);
  };
  document.getElementById("btn-eq-zero").onclick = () => {
    state.eqConfig.preampDb = 0;
    state.eqConfig.bassBoost = 0;
    state.eqConfig.bandGains = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    state.eqConfig.presetName = "Flat";
    persistEq();
    renderEqualizer(container);
  };
  document.getElementById("eq-preset-sel").onchange = (e) => {
    const p = e.target.value;
    if (EQ_PRESETS[p]) {
      state.eqConfig.enabled = true;
      state.eqConfig.bandGains = [...EQ_PRESETS[p]];
      state.eqConfig.presetName = p;
      persistEq();
      renderEqualizer(container);
    }
  };
  slPreamp.oninput = (e) => {
    const v = parseFloat(e.target.value);
    updateSliderFill(slPreamp);
    state.eqConfig.enabled = true;
    state.eqConfig.preampDb = v;
    document.getElementById("val-preamp").textContent = `${v > 0 ? "+" : ""}${v.toFixed(1)} dB`;
    persistEq(true);
  };
  slBass.oninput = (e) => {
    const v = parseFloat(e.target.value);
    updateSliderFill(slBass);
    state.eqConfig.enabled = true;
    state.eqConfig.bassBoost = v;
    document.getElementById("val-bass").textContent = `+${(v * 12).toFixed(1)} dB`;
    persistEq(true);
  };
  container.querySelectorAll(".sl-band").forEach((sl) => {
    sl.oninput = (e) => {
      updateSliderFill(sl);
      const idx = parseInt(sl.dataset.band, 10);
      const v = parseFloat(e.target.value);
      state.eqConfig.enabled = true;
      state.eqConfig.bandGains[idx] = v;
      state.eqConfig.presetName = "Custom";
      document.getElementById(`val-b-${idx}`).textContent = `${v > 0 ? "+" : ""}${v.toFixed(1)}`;
      persistEq(true);
    };
  });
}

async function renderSettings(container) {
  const s = state.settings;
  const rawTab = state.settingsTab || "customization";
  const tab = (rawTab === "appearance" || rawTab === "backgrounds") ? "customization" : rawTab;
  state.settingsTab = tab;
  const { duplicateEntries } = computeLibraryVisibility();
  const hiddenCount = (s.hiddenTrackIds || []).length;

  let nativeDevices = [];
  let webDevices = [];
  if (tab === "system") {
    if (state.nativeAvailable) {
      try {
        const [devs, st] = await Promise.all([api.nativeGetDevices(), api.nativeGetStatus()]);
        nativeDevices = devs || [];
        if (st) nativeStatusCache = st;
      } catch (_) {}
    } else {
      try {
        const all = await navigator.mediaDevices.enumerateDevices();
        webDevices = all
          .filter((d) => d.kind === "audiooutput")
          .map((d) => ({ id: d.deviceId, name: d.label || `Устройство (${d.deviceId.slice(0, 8)})`, isDefault: false }));
      } catch (_) {}
    }
  }
  const dacList = state.nativeAvailable ? nativeDevices : webDevices;

  const buildBgCardHtml = (prefix, title, mode, solidColor, colorMode, customPath, blurPx, dimPct) => `
    <div class="mono-card">
      <div class="nav-label" style="padding:0 0 10px;">${title}</div>
      <div class="setting-line">
        <div><div style="font-size:13px;font-weight:700;">Режим фона</div></div>
        <select id="set-${prefix}-bg-mode" class="mono-select">
          <option value="none" ${mode === "none" ? "selected" : ""}>Сплошной цвет</option>
          <option value="cover" ${mode === "cover" ? "selected" : ""}>Размытая обложка</option>
          <option value="custom" ${mode === "custom" ? "selected" : ""}>Свой файл</option>
        </select>
      </div>
      <div class="setting-line">
        <div><div style="font-size:13px;font-weight:700;">Сплошной цвет фона</div></div>
        <div class="color-picker-row">
          <span id="lbl-${prefix}-bg-solid-hex" style="font-size:11px;font-weight:700;color:var(--fg-2);">${escapeHtml(solidColor || "#000000")}</span>
          <input id="set-${prefix}-bg-solid" type="color" value="${escapeHtml(solidColor || "#000000")}" class="mono-color-input" />
        </div>
      </div>
      <div class="setting-line">
        <div style="font-size:11.5px;color:var(--fg-2);overflow:hidden;text-overflow:ellipsis;">
          ${customPath ? `Файл: ${escapeHtml(customPath.split(/[\\/]/).pop())}` : "Файл не выбран"}
        </div>
        <button id="btn-pick-${prefix}-bg" class="mono-btn tiny">Выбрать файл...</button>
      </div>
      <div class="setting-line">
        <div><div style="font-size:13px;font-weight:700;">Цветовой режим</div></div>
        <select id="set-${prefix}-bg-color" class="mono-select">
          <option value="mono" ${(colorMode || "mono") === "mono" ? "selected" : ""}>Монохром</option>
          <option value="color" ${colorMode === "color" ? "selected" : ""}>Цветной</option>
        </select>
      </div>
      <div class="setting-line">
        <div><div style="font-size:13px;font-weight:700;">Размытие: <span id="lbl-${prefix}-bg-blur">${blurPx} px</span></div></div>
        <input id="set-${prefix}-bg-blur" type="range" min="0" max="90" step="2" value="${blurPx}" class="mobile-slider" style="max-width:130px;" />
      </div>
      <div class="setting-line">
        <div><div style="font-size:13px;font-weight:700;">Затемнение: <span id="lbl-${prefix}-bg-dim">${dimPct}%</span></div></div>
        <input id="set-${prefix}-bg-dim" type="range" min="15" max="95" step="5" value="${dimPct}" class="mobile-slider" style="max-width:130px;" />
      </div>
    </div>
  `;

  const TABS = [
    ["customization", "Кастомизация"],
    ["library", "Библиотека"],
    ["system", "Звук и система"],
  ];

  let bodyHtml = "";
  if (tab === "customization") {
    bodyHtml = `
      <div class="cards-stack">
        <div class="mono-card">
          <div class="nav-label" style="padding:0 0 10px;">ТЕМА И ПРЕСЕТЫ</div>
          <div class="setting-line" style="flex-direction:column;align-items:flex-start;gap:6px;">
            <div style="font-size:12px;font-weight:700;">Готовые пресеты</div>
            <div class="theme-presets-grid">
              ${THEME_PRESETS.map(
                (p) => `
                <button class="theme-preset-chip" data-preset="${p.id}">
                  <span class="preset-dot" style="background:${p.accentColor};"></span>
                  <span>${escapeHtml(p.name)}</span>
                </button>`
              ).join("")}
            </div>
          </div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">Акцентный цвет</div>
              <div class="view-subtitle">Активные элементы, ползунки и спектр</div>
            </div>
            <div class="color-picker-row">
              <span id="lbl-accent-hex" style="font-size:11px;font-weight:700;color:var(--fg-2);">${escapeHtml(s.accentColor || "#ffffff")}</span>
              <input id="set-accent-color" type="color" value="${escapeHtml(s.accentColor || "#ffffff")}" class="mono-color-input" />
            </div>
          </div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">Цвет меню и панелей</div>
              <div class="view-subtitle">Оттенок сайдбара, шапки и карточек</div>
            </div>
            <div class="color-picker-row">
              <span id="lbl-panel-hex" style="font-size:11px;font-weight:700;color:var(--fg-2);">${escapeHtml(s.panelColor || "#0a0a0a")}</span>
              <input id="set-panel-color" type="color" value="${escapeHtml(s.panelColor || "#0a0a0a")}" class="mono-color-input" />
            </div>
          </div>
          <div style="display:flex;gap:8px;margin-top:12px;">
            <button id="btn-theme-reset" class="mono-btn">Сбросить тему</button>
          </div>
        </div>

        <div class="mono-card">
          <div class="nav-label" style="padding:0 0 10px;">СТЕКЛО, ПРОЗРАЧНОСТЬ, ШРИФТ И ТЕКСТ</div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Шрифт приложения (офлайн)</div></div>
            <select id="set-font" class="mono-select">
              <option value="jakarta" ${s.fontFamily === "jakarta" ? "selected" : ""}>Google Sans / Jakarta</option>
              <option value="manrope" ${s.fontFamily === "manrope" ? "selected" : ""}>Manrope</option>
              <option value="inter" ${s.fontFamily === "inter" ? "selected" : ""}>Inter</option>
              <option value="mono" ${s.fontFamily === "mono" ? "selected" : ""}>JetBrains Mono</option>
              <option value="system" ${s.fontFamily === "system" ? "selected" : ""}>Системный</option>
            </select>
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Яркость цвета меню: <span id="lbl-panel-bright">${s.panelBrightness ?? 100}%</span></div></div>
            <input id="set-panel-bright" type="range" min="40" max="220" step="5" value="${s.panelBrightness ?? 100}" class="mobile-slider" style="max-width:130px;" />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Прозрачность меню: <span id="lbl-panel-op">${s.panelOpacity ?? 88}%</span></div></div>
            <input id="set-panel-op" type="range" min="20" max="100" step="2" value="${s.panelOpacity ?? 88}" class="mobile-slider" style="max-width:130px;" />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Прозрачность карточек: <span id="lbl-card-op">${s.cardOpacity ?? 86}%</span></div></div>
            <input id="set-card-op" type="range" min="20" max="100" step="2" value="${s.cardOpacity ?? 86}" class="mobile-slider" style="max-width:130px;" />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Блюр панелей и карточек: <span id="lbl-panel-blur">${s.panelBlurPx ?? 14} px</span></div></div>
            <input id="set-panel-blur" type="range" min="0" max="40" step="2" value="${s.panelBlurPx ?? 14}" class="mobile-slider" style="max-width:130px;" />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Скругление углов: <span id="lbl-radius">${s.borderRadiusPx ?? 12} px</span></div></div>
            <input id="set-radius" type="range" min="2" max="22" step="1" value="${s.borderRadiusPx ?? 12}" class="mobile-slider" style="max-width:130px;" />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Яркость рамок: <span id="lbl-border-op">${s.borderOpacity ?? 10}%</span></div></div>
            <input id="set-border-op" type="range" min="0" max="35" step="1" value="${s.borderOpacity ?? 10}" class="mobile-slider" style="max-width:130px;" />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Блюр неактивных строк текста: <span id="lbl-lyrics-blur">${s.lyricsBlurIntensity ?? 100}%</span></div></div>
            <input id="set-lyrics-blur" type="range" min="0" max="180" step="10" value="${s.lyricsBlurIntensity ?? 100}" class="mobile-slider" style="max-width:130px;" />
          </div>
        </div>

        ${buildBgCardHtml("app", "ФОН ГЛАВНОГО ОКНА", s.appBgMode || "none", s.appBgSolidColor, s.appBgColorMode, s.appBgCustomPath, s.appBgBlurPx ?? 48, s.appBgDimPercent ?? 68)}
        ${buildBgCardHtml("fs", "ФОН ПОЛНОЭКРАННОГО ПЛЕЕРА", s.fsBgMode || "cover", s.fsBgSolidColor, s.fsBgColorMode, s.fsBgCustomPath, s.fsBgBlurPx ?? 52, s.fsBgDimPercent ?? 64)}
      </div>
    `;
  } else if (tab === "library") {
    bodyHtml = `
      <div class="cards-stack">
        <div class="mono-card">
          <div class="nav-label" style="padding:0 0 10px;">СПИСКИ, ОБЛОЖКИ И ТЕКСТЫ</div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">Значки качества треков</div>
              <div class="view-subtitle">В списке, мини-плеере и полноэкранном режиме</div>
            </div>
            <input id="set-quality-badges" type="checkbox" ${s.showQualityBadges ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Компактный список треков</div></div>
            <input id="set-compact" type="checkbox" ${s.compactMode ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Номера треков</div></div>
            <input id="set-nums" type="checkbox" ${s.showTrackNumbers ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">Онлайн-поиск текстов (LRCLIB)</div>
              <div class="view-subtitle">Локальные .lrc/.txt и встроенные теги работают всегда</div>
            </div>
            <input id="set-online-lyrics" type="checkbox" ${s.onlineLyricsEnabled !== false ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">Автоподгрузка обложек (онлайн)</div>
              <div class="view-subtitle">Только для треков без встроенной или локальной обложки</div>
            </div>
            <input id="set-online-covers" type="checkbox" ${s.onlineCoversEnabled !== false ? "checked" : ""} />
          </div>
        </div>

        <div class="mono-card">
          <div class="nav-label" style="padding:0 0 10px;">ПАПКИ, СКРЫТЫЕ И ДУБЛИКАТЫ</div>
          ${
            state.folders.length === 0
              ? `<div class="view-subtitle" style="margin-bottom:10px;">Папки не добавлены</div>`
              : state.folders
                  .map(
                    (f) => `
                <div class="setting-line">
                  <span style="font-size:12px;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(f)}</span>
                  <button class="mono-btn tiny btn-rm-folder" data-folder="${escapeHtml(f)}">Удалить</button>
                </div>`
                  )
                  .join("")
          }
          <div style="display:flex;gap:8px;margin:10px 0 14px;">
            <button id="btn-set-add-folder" class="mono-btn solid">Добавить папку</button>
            <button id="btn-set-rescan" class="mono-btn">Пересканировать</button>
          </div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">Скрытые треки (${hiddenCount})</div>
              <div class="view-subtitle">Скрыты вручную через меню трека</div>
            </div>
            <button id="btn-restore-hidden" class="mono-btn tiny" ${hiddenCount === 0 ? "disabled style='opacity:0.45;'" : ""}>Вернуть все</button>
          </div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">Скрывать одинаковые треки</div>
              <div class="view-subtitle">Оставлять версию с наилучшим качеством</div>
            </div>
            <input id="set-hide-dups" type="checkbox" ${s.hideDuplicates ? "checked" : ""} />
          </div>
          ${
            s.hideDuplicates
              ? `<div class="setting-line">
                  <div>
                    <div style="font-size:12.5px;font-weight:700;">Найдено дубликатов: ${duplicateEntries.length}</div>
                  </div>
                  ${duplicateEntries.length ? `<button id="btn-toggle-dups" class="mono-btn tiny">${state.duplicatesExpanded ? "Свернуть" : "Показать"}</button>` : ""}
                </div>
                ${
                  state.duplicatesExpanded && duplicateEntries.length
                    ? duplicateEntries
                        .slice(0, 25)
                        .map(
                          (d) => `
                        <div class="setting-line">
                          <div style="min-width:0;">
                            <div style="font-size:12px;font-weight:700;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(d.track.title)} — ${escapeHtml(d.track.artist)}</div>
                            <div class="view-subtitle">${escapeHtml(formatTrackBadge(d.track))} (оставлен ${escapeHtml(formatTrackBadge(d.keptTrack))})</div>
                          </div>
                          <button class="mono-btn tiny btn-unhide-dup" data-dup-id="${escapeHtml(d.track.id)}">Показать</button>
                        </div>`
                        )
                        .join("")
                    : ""
                }`
              : ""
          }
        </div>
      </div>
    `;
  } else {
    bodyHtml = `
      <div class="cards-stack">
        <div class="mono-card">
          <div class="nav-label" style="padding:0 0 10px;">АУДИО-ДВИЖОК И ЦАП</div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">Активный движок</div>
              <div class="view-subtitle">${
                state.nativeAvailable
                  ? nativeStatusCache.exclusiveActual
                    ? `Прямой вывод без микшера (${escapeHtml(nativeStatusCache.backendName || "Exclusive")}${nativeStatusCache.sampleRate ? ` • ${(nativeStatusCache.sampleRate / 1000).toFixed(1)} kHz` : ""}${nativeStatusCache.bitDepthOut ? ` • ${nativeStatusCache.bitDepthOut}-bit` : ""}${nativeStatusCache.activeDeviceName ? ` • ${escapeHtml(nativeStatusCache.activeDeviceName)}` : ""})`
                    : `Native C++ (dr_flac / minimp3 / dr_wav + ${escapeHtml(nativeStatusCache.backendName || "miniaudio Shared")})`
                  : "Chromium WebAudio (нативный модуль недоступен)"
              }</div>
            </div>
            <span class="format-tag">${
              state.nativeAvailable
                ? nativeStatusCache.exclusiveActual
                  ? "EXCLUSIVE"
                  : "C++ NATIVE"
                : "WEBAUDIO"
            }</span>
          </div>
          <div class="setting-line" style="flex-direction:column;align-items:stretch;gap:8px;">
            <div style="font-size:13px;font-weight:700;">Устройство вывода звука${state.nativeAvailable && s.exclusiveMode ? " (аппаратный ЦАП)" : ""}</div>
            <select id="set-dac-select" class="mono-select" style="width:100%;">
              <option value="default">${s.exclusiveMode ? "Автовыбор аппаратного ЦАП" : "Системный выход по умолчанию"}</option>
              ${dacList
                .map(
                  (d) =>
                    `<option value="${escapeHtml(d.id)}" ${s.outputDeviceId === d.id ? "selected" : ""}>${escapeHtml(d.name)}${d.isDefault ? " (По умолч.)" : ""}</option>`
                )
                .join("")}
            </select>
          </div>
          <div class="setting-line">
            <div style="min-width:0;padding-right:10px;">
              <div style="font-size:13px;font-weight:700;">Прямой вывод Bit-Perfect (Exclusive)</div>
              <div class="view-subtitle">Эксклюзивный захват ЦАП (WASAPI Exclusive / ALSA Direct): обходит микшер ОС, другие приложения не могут воспроизводить звук на этом ЦАП</div>
              ${
                s.exclusiveMode && nativeStatusCache.lastError
                  ? `<div class="view-subtitle" style="color:#ff7b7b;margin-top:4px;">${escapeHtml(nativeStatusCache.lastError)}</div>`
                  : ""
              }
            </div>
            <input id="set-exclusive" type="checkbox" ${s.exclusiveMode ? "checked" : ""} ${state.nativeAvailable ? "" : "disabled"} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Кроссфейд: <span id="lbl-cf">${s.crossfadeSeconds} сек</span></div></div>
            <input id="set-cf" type="range" min="0" max="10" step="1" value="${s.crossfadeSeconds}" class="mobile-slider" style="max-width:120px;" />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">ReplayGain (выравнивание громкости)</div></div>
            <input id="set-rg" type="checkbox" ${s.replayGainEnabled ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Gapless (без пауз между треками)</div></div>
            <input id="set-gapless" type="checkbox" ${s.gaplessEnabled ? "checked" : ""} />
          </div>
        </div>

        <div class="mono-card">
          <div class="nav-label" style="padding:0 0 10px;">ВИЗУАЛИЗАТОР И СИСТЕМА</div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Визуализатор спектра</div></div>
            <input id="set-vis-on" type="checkbox" ${s.visualizerEnabled ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Стиль визуализатора</div></div>
            <select id="set-vis-style" class="mono-select">
              <option value="bars" ${s.visualizerStyle === "bars" ? "selected" : ""}>Столбики</option>
              <option value="wave" ${s.visualizerStyle === "wave" ? "selected" : ""}>Волна</option>
              <option value="mirror" ${s.visualizerStyle === "mirror" ? "selected" : ""}>Импульс</option>
            </select>
          </div>
          <div class="setting-line">
            <div>
              <div style="font-size:13px;font-weight:700;">О программе</div>
              <div class="view-subtitle">Zuna Desktop v1.1.0 • Bit-Transparent Audio</div>
            </div>
            <span class="format-tag">v1.1.0</span>
          </div>
        </div>

        <div class="mono-card">
          <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;padding-bottom:10px;">
            <div class="nav-label" style="padding:0;">ЭНЕРГОСБЕРЕЖЕНИЕ</div>
            <span class="power-status-pill ${getEffectivePowerSaver().active ? "active" : ""}">
              ${(() => {
                const eff = getEffectivePowerSaver();
                if (eff.mode === "battery_saver" || eff.autoTriggered) return "Battery Saver";
                if (eff.mode === "balanced") return "Balanced";
                return eff.active ? "Настроено" : "Standard";
              })()}
            </span>
          </div>
          <div class="view-subtitle" style="margin-bottom:10px;">Аудиотракт, частота дискретизации, Gapless и Bit-perfect не затрагиваются</div>
          <div class="power-radio-list">
            <label class="power-radio-item ${(s.powerSaverMode || "standard") === "standard" ? "active" : ""}">
              <input type="radio" name="power-mode" value="standard" ${(s.powerSaverMode || "standard") === "standard" ? "checked" : ""} />
              <div>
                <div style="font-size:13px;font-weight:700;">Выключено (Standard)</div>
                <div class="view-subtitle">Весь визуальный шик, полный blur и плавные анимации</div>
              </div>
            </label>
            <label class="power-radio-item ${s.powerSaverMode === "balanced" ? "active" : ""}">
              <input type="radio" name="power-mode" value="balanced" ${s.powerSaverMode === "balanced" ? "checked" : ""} />
              <div>
                <div style="font-size:13px;font-weight:700;">Balanced</div>
                <div class="view-subtitle">Упрощённые эффекты blur, меньше анимаций и редкий тикер UI</div>
              </div>
            </label>
            <label class="power-radio-item ${s.powerSaverMode === "auto" ? "active" : ""}">
              <input type="radio" name="power-mode" value="auto" ${s.powerSaverMode === "auto" ? "checked" : ""} />
              <div>
                <div style="font-size:13px;font-weight:700;">Автоматически при низком заряде</div>
                <div class="view-subtitle">${state.batteryInfoKnown ? `Батарея: ${state.batteryLevelPct}%${state.batteryCharging ? " (зарядка)" : ""}` : "Включает Battery Saver при разряде ≤ 20%"}</div>
              </div>
            </label>
            <label class="power-radio-item ${s.powerSaverMode === "battery_saver" ? "active" : ""}">
              <input type="radio" name="power-mode" value="battery_saver" ${s.powerSaverMode === "battery_saver" ? "checked" : ""} />
              <div>
                <div style="font-size:13px;font-weight:700;">Всегда включено (Battery Saver)</div>
                <div class="view-subtitle">Минимум графических эффектов, статичный фон, без визуализатора</div>
              </div>
            </label>
          </div>
          <div class="nav-label" style="padding:6px 0 8px;">СНИЖЕНИЕ НАГРУЗКИ (РУЧНОЕ УПРАВЛЕНИЕ)</div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Отключить тяжёлый blur</div></div>
            <input id="set-ps-blur" type="checkbox" ${s.powerDisableBlur ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Уменьшить анимации и переходы</div></div>
            <input id="set-ps-anim" type="checkbox" ${s.powerReduceAnimations ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Отключить динамический фон</div></div>
            <input id="set-ps-bg" type="checkbox" ${s.powerDisableDynamicBg ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Отключить визуализатор</div></div>
            <input id="set-ps-vis" type="checkbox" ${s.powerDisableVisualizer ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Ограничить частоту обновления UI</div></div>
            <input id="set-ps-ui" type="checkbox" ${s.powerLimitUiRefresh ? "checked" : ""} />
          </div>
          <div class="setting-line">
            <div><div style="font-size:13px;font-weight:700;">Кэшированные миниатюры обложек</div></div>
            <input id="set-ps-thumb" type="checkbox" ${s.powerUseThumbCovers ? "checked" : ""} />
          </div>
        </div>
      </div>
    `;
  }

  container.innerHTML = `
    <div class="view-header">
      <div><div class="view-title">Настройки</div></div>
      <div class="view-actions">
        <button id="btn-theme-import" class="mono-btn">Импорт темы</button>
        <button id="btn-theme-export" class="mono-btn solid">Поделиться темой</button>
      </div>
    </div>
    <div class="settings-tabs-row">
      ${TABS.map(([id, lbl]) => `<button class="settings-tab-btn ${tab === id ? "active" : ""}" data-stab="${id}">${lbl}</button>`).join("")}
    </div>
    ${bodyHtml}
  `;

  container.querySelectorAll(".settings-tab-btn").forEach((b) => {
    b.onclick = () => {
      state.settingsTab = b.dataset.stab;
      renderSettings(container);
    };
  });

  const save = (debounced = false) => {
    applyAppearanceSettings();
    applyVolumeToAudioGraph();
    if (debounced) scheduleSaveSettings();
    else api.saveSettings(state.settings);
  };

  document.getElementById("btn-theme-export").onclick = () => {
    const expModal = document.getElementById("export-theme-modal");
    const inp = document.getElementById("export-theme-name");
    inp.value = inp.value || "ZunaTheme";
    expModal.classList.remove("hidden");
    inp.focus();
  };
  document.getElementById("btn-theme-import").onclick = async () => {
    const res = await api.importTheme();
    if (res && !res.canceled) {
      if (res.error) {
        showToast(`Ошибка темы: ${res.error}`);
      } else if (res.settings) {
        state.settings = { ...state.settings, ...res.settings };
        applyAppearanceSettings();
        renderSettings(container);
        showToast(`Тема «${res.name || "ZunaTheme"}» применена`);
      }
    }
  };

  const bindRangeSetting = (inputId, labelId, prop, suffix) => {
    const el = document.getElementById(inputId);
    if (!el) return;
    updateSliderFill(el);
    el.oninput = (e) => {
      updateSliderFill(el);
      state.settings[prop] = parseInt(e.target.value, 10);
      document.getElementById(labelId).textContent = `${state.settings[prop]}${suffix}`;
      if (prop === "lyricsBlurIntensity") updateActiveLyricLine(true);
      save(true);
    };
  };

  if (tab === "customization") {
    container.querySelectorAll(".theme-preset-chip").forEach((btn) => {
      btn.onclick = () => {
        const preset = THEME_PRESETS.find((p) => p.id === btn.dataset.preset);
        if (!preset) return;
        Object.assign(state.settings, {
          accentColor: preset.accentColor,
          panelColor: preset.panelColor,
          panelBrightness: preset.panelBrightness,
          panelOpacity: preset.panelOpacity,
          cardOpacity: preset.cardOpacity,
          panelBlurPx: preset.panelBlurPx,
          borderRadiusPx: preset.borderRadiusPx,
          borderOpacity: preset.borderOpacity,
        });
        save();
        renderSettings(container);
      };
    });
    document.getElementById("btn-theme-reset").onclick = () => {
      Object.assign(state.settings, {
        accentColor: "#ffffff",
        panelColor: "#0a0a0a",
        panelBrightness: 100,
        panelOpacity: 88,
        cardOpacity: 86,
        panelBlurPx: 14,
        borderRadiusPx: 12,
        borderOpacity: 10,
        lyricsBlurIntensity: 100,
      });
      save();
      renderSettings(container);
      showToast("Тема сброшена на стандартную");
    };
    document.getElementById("set-accent-color").oninput = (e) => {
      state.settings.accentColor = e.target.value;
      document.getElementById("lbl-accent-hex").textContent = e.target.value;
      save(true);
    };
    document.getElementById("set-panel-color").oninput = (e) => {
      state.settings.panelColor = e.target.value;
      document.getElementById("lbl-panel-hex").textContent = e.target.value;
      save(true);
    };
    document.getElementById("set-font").onchange = (e) => { state.settings.fontFamily = e.target.value; save(); };
    bindRangeSetting("set-panel-bright", "lbl-panel-bright", "panelBrightness", "%");
    bindRangeSetting("set-panel-op", "lbl-panel-op", "panelOpacity", "%");
    bindRangeSetting("set-card-op", "lbl-card-op", "cardOpacity", "%");
    bindRangeSetting("set-panel-blur", "lbl-panel-blur", "panelBlurPx", " px");
    bindRangeSetting("set-radius", "lbl-radius", "borderRadiusPx", " px");
    bindRangeSetting("set-border-op", "lbl-border-op", "borderOpacity", "%");
    bindRangeSetting("set-lyrics-blur", "lbl-lyrics-blur", "lyricsBlurIntensity", "%");

    const bindBgCard = (prefix, modeKey, solidKey, colorModeKey, pathKey, isVideoKey, blurKey, dimKey) => {
      document.getElementById(`set-${prefix}-bg-mode`).onchange = (e) => {
        state.settings[modeKey] = e.target.value;
        save();
      };
      document.getElementById(`set-${prefix}-bg-solid`).oninput = (e) => {
        state.settings[solidKey] = e.target.value;
        document.getElementById(`lbl-${prefix}-bg-solid-hex`).textContent = e.target.value;
        save(true);
      };
      document.getElementById(`set-${prefix}-bg-color`).onchange = (e) => {
        state.settings[colorModeKey] = e.target.value;
        save();
      };
      document.getElementById(`btn-pick-${prefix}-bg`).onclick = async () => {
        const picked = await api.pickBackgroundFile();
        if (picked) {
          state.settings[pathKey] = picked.path;
          state.settings[isVideoKey] = picked.isVideo;
          state.settings[modeKey] = "custom";
          save();
          renderSettings(container);
        }
      };
      bindRangeSetting(`set-${prefix}-bg-blur`, `lbl-${prefix}-bg-blur`, blurKey, " px");
      bindRangeSetting(`set-${prefix}-bg-dim`, `lbl-${prefix}-bg-dim`, dimKey, "%");
    };
    bindBgCard("app", "appBgMode", "appBgSolidColor", "appBgColorMode", "appBgCustomPath", "appBgIsVideo", "appBgBlurPx", "appBgDimPercent");
    bindBgCard("fs", "fsBgMode", "fsBgSolidColor", "fsBgColorMode", "fsBgCustomPath", "fsBgIsVideo", "fsBgBlurPx", "fsBgDimPercent");
  } else if (tab === "library") {
    document.getElementById("set-quality-badges").onchange = (e) => { state.settings.showQualityBadges = e.target.checked; save(); };
    document.getElementById("set-compact").onchange = (e) => { state.settings.compactMode = e.target.checked; save(); };
    document.getElementById("set-nums").onchange = (e) => { state.settings.showTrackNumbers = e.target.checked; save(); };
    document.getElementById("set-online-lyrics").onchange = (e) => { state.settings.onlineLyricsEnabled = e.target.checked; save(); };
    document.getElementById("set-online-covers").onchange = (e) => {
      state.settings.onlineCoversEnabled = e.target.checked;
      onlineCoverRequestedIds.clear();
      if (!e.target.checked) {
        onlineCoverQueue.length = 0;
      }
      save();
      updateBarNowPlaying();
      updateBackgroundLayers();
      if (e.target.checked && state.currentTrack) {
        requestOnlineCoverForTrack(state.currentTrack, true);
      }
    };
    document.getElementById("btn-set-add-folder").onclick = handlePickFolder;
    document.getElementById("btn-set-rescan").onclick = handleRescanLibrary;
    container.querySelectorAll(".btn-rm-folder").forEach((b) => {
      b.onclick = async () => {
        const res = await api.removeFolder(b.dataset.folder);
        state.folders = res.folders;
        state.tracks = res.tracks;
        updateCountsAndBadges();
        renderSettings(container);
      };
    });
    document.getElementById("btn-restore-hidden").onclick = () => {
      state.settings.hiddenTrackIds = [];
      save();
      renderSettings(container);
      showToast("Все скрытые треки восстановлены");
    };
    document.getElementById("set-hide-dups").onchange = (e) => {
      state.settings.hideDuplicates = e.target.checked;
      save();
      renderSettings(container);
    };
    const toggleDupsBtn = document.getElementById("btn-toggle-dups");
    if (toggleDupsBtn) {
      toggleDupsBtn.onclick = () => {
        state.duplicatesExpanded = !state.duplicatesExpanded;
        renderSettings(container);
      };
    }
    container.querySelectorAll(".btn-unhide-dup").forEach((b) => {
      b.onclick = () => {
        const list = new Set(state.settings.forceVisibleDuplicateIds || []);
        list.add(b.dataset.dupId);
        state.settings.forceVisibleDuplicateIds = Array.from(list);
        save();
        renderSettings(container);
      };
    });
  } else {
    document.getElementById("set-dac-select").onchange = async (e) => {
      const id = e.target.value;
      state.settings.outputDeviceId = id;
      let ok = true;
      if (state.nativeAvailable) {
        ok = await api.nativeSetDevice(id);
        try {
          const st = await api.nativeGetStatus();
          if (st) nativeStatusCache = st;
        } catch (_) {}
      } else {
        for (const d of decks) {
          if (typeof d.el.setSinkId === "function") {
            try { await d.el.setSinkId(id === "default" ? "" : id); } catch (_) {}
          }
        }
      }
      api.saveSettings(state.settings);
      if (!ok && nativeStatusCache.lastError) {
        showToast(nativeStatusCache.lastError);
      } else {
        showToast("Устройство вывода обновлено");
      }
      if (state.nativeAvailable) {
        renderSettings(container);
      }
    };
    document.getElementById("set-exclusive").onchange = async (e) => {
      const enabled = e.target.checked;
      state.settings.exclusiveMode = enabled;
      let ok = true;
      if (state.nativeAvailable) {
        ok = await api.nativeSetExclusive(enabled);
        try {
          const st = await api.nativeGetStatus();
          if (st) nativeStatusCache = st;
        } catch (_) {}
      }
      api.saveSettings(state.settings);
      if (enabled) {
        if (ok) {
          showToast("Эксклюзивный захват ЦАП включён");
        } else {
          showToast(nativeStatusCache.lastError || "Не удалось эксклюзивно захватить ЦАП");
        }
      } else {
        showToast("Эксклюзивный режим ЦАП выключен");
      }
      updateBarNowPlaying();
      renderSettings(container);
    };
    bindRangeSetting("set-cf", "lbl-cf", "crossfadeSeconds", " сек");
    document.getElementById("set-rg").onchange = (e) => {
      state.settings.replayGainEnabled = e.target.checked;
      if (state.nativeAvailable) api.nativeSetReplayGain(e.target.checked);
      save();
    };
    document.getElementById("set-gapless").onchange = (e) => {
      state.settings.gaplessEnabled = e.target.checked;
      if (state.nativeAvailable) api.nativeSetGapless(e.target.checked);
      save();
    };
    document.getElementById("set-vis-on").onchange = (e) => { state.settings.visualizerEnabled = e.target.checked; save(); };
    document.getElementById("set-vis-style").onchange = (e) => { state.settings.visualizerStyle = e.target.value; save(); };

    container.querySelectorAll('input[name="power-mode"]').forEach((radio) => {
      radio.onchange = (e) => {
        if (!e.target.checked) return;
        applyPowerSaverPreset(e.target.value);
        save();
        renderSettings(container);
      };
    });
    const bindPowerCheck = (id, key) => {
      const el = document.getElementById(id);
      if (el) {
        el.onchange = (e) => {
          state.settings[key] = e.target.checked;
          save();
          renderSettings(container);
        };
      }
    };
    bindPowerCheck("set-ps-blur", "powerDisableBlur");
    bindPowerCheck("set-ps-anim", "powerReduceAnimations");
    bindPowerCheck("set-ps-bg", "powerDisableDynamicBg");
    bindPowerCheck("set-ps-vis", "powerDisableVisualizer");
    bindPowerCheck("set-ps-ui", "powerLimitUiRefresh");
    bindPowerCheck("set-ps-thumb", "powerUseThumbCovers");
  }
}

/* LINUX / NOCTALIA MPRIS MEDIA SESSION METADATA & CONTROLS */
function getMprisLoopStatus() {
  if (state.repeatMode === "one") return "Track";
  if (state.repeatMode === "all") return "Playlist";
  return "None";
}

function pushNativeMprisState(seeked = false) {
  if (!api || typeof api.mprisUpdate !== "function") return;
  const track = state.currentTrack;
  const durSec = track
    ? Math.max(0, getCurrentPlaybackDurationSec() || (track.durationMs || 0) / 1000)
    : 0;
  const posSec = track ? Math.max(0, getCurrentPlaybackTimeSec()) : 0;
  const playbackStatus = (!track || state.isStopped)
    ? "Stopped"
    : state.isPlaying
      ? "Playing"
      : "Paused";
  const coverPath = track ? (getEffectiveTrackCover(track, true) || getEffectiveTrackCover(track, false) || "") : "";
  api.mprisUpdate({
    hasTrack: Boolean(track),
    trackId: track?.id || "",
    title: track?.title || "",
    artist: track?.artist || "",
    album: track?.album || "",
    coverPath,
    filePath: track?.filePath || "",
    durationSec: Number.isFinite(durSec) ? durSec : 0,
    positionSec: Number.isFinite(posSec) ? posSec : 0,
    playbackStatus,
    loopStatus: getMprisLoopStatus(),
    shuffle: Boolean(state.isShuffle),
    volume: Math.max(0, Math.min(1, state.settings.volume ?? 0.85)),
    seeked: Boolean(seeked),
  });
}

async function syncMprisMediaSession(track) {
  pushNativeMprisState(false);
  if (!("mediaSession" in navigator) || !track) return;
  try {
    let artworkList = [];
    const effCover = getEffectiveTrackCover(track, true);
    if (effCover) {
      const dataUrl = await api.getCoverDataUrl(effCover);
      if (dataUrl) {
        const mime = dataUrl.startsWith("data:image/png") ? "image/png" : "image/jpeg";
        artworkList = [{ src: dataUrl, sizes: "512x512", type: mime }];
      }
    }
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.title || "Unknown Track",
      artist: track.artist || "Unknown Artist",
      album: track.album || "",
      artwork: artworkList,
    });
    navigator.mediaSession.playbackState = state.isPlaying ? "playing" : "paused";
  } catch (e) {
    console.warn("MediaSession update failed:", e);
  }
}

function updateMprisPositionState(seeked = false) {
  pushNativeMprisState(seeked);
  if (!("mediaSession" in navigator) || !navigator.mediaSession.setPositionState) return;
  const dur = getCurrentPlaybackDurationSec();
  const pos = getCurrentPlaybackTimeSec();
  if (Number.isFinite(dur) && dur > 0 && Number.isFinite(pos) && pos >= 0 && pos <= dur) {
    try {
      navigator.mediaSession.setPositionState({
        duration: dur,
        playbackRate: activeBackend === "native" ? 1.0 : getActiveAudio().playbackRate || 1.0,
        position: pos,
      });
    } catch (_) {}
  }
}

/* SLEEP TIMER CONTROLLER */
function updateSleepTimerUi() {
  const badge = document.getElementById("sleep-timer-badge");
  const topBtn = document.getElementById("btn-sleep-timer");
  const fsBtn = document.getElementById("btn-fs-sleep");
  const modalStatus = document.getElementById("sleep-status-text");

  let label = "Сон";
  let modalText = "Таймер отключён";
  const isActive = state.sleepMode !== "off";

  if (state.sleepMode === "end_of_track") {
    label = "До конца";
    modalText = "Пауза после окончания текущего трека";
  } else if (state.sleepMode === "timer") {
    const remMs = Math.max(0, state.sleepEndTimeMs - Date.now());
    const totalSec = Math.ceil(remMs / 1000);
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    label = `${min}:${sec < 10 ? "0" : ""}${sec}`;
    modalText = `Осталось: ${label}`;
  }

  if (badge) badge.textContent = label;
  if (topBtn) topBtn.classList.toggle("active", isActive);
  if (fsBtn) fsBtn.classList.toggle("active", isActive);
  if (modalStatus) modalStatus.textContent = modalText;
}

function cancelSleepTimer(silent = false) {
  if (state.sleepTimerId) {
    clearInterval(state.sleepTimerId);
    state.sleepTimerId = null;
  }
  state.sleepMode = "off";
  state.sleepEndTimeMs = 0;
  updateSleepTimerUi();
  if (!silent) showToast("Таймер сна отключён");
}

function triggerSleepPause() {
  cancelSleepTimer(true);
  if (state.isPlaying) {
    togglePlayPause();
  }
  showToast("Таймер сна: воспроизведение остановлено", 3400);
}

function startSleepTimerMinutes(minutes) {
  cancelSleepTimer(true);
  state.sleepMode = "timer";
  state.sleepEndTimeMs = Date.now() + minutes * 60 * 1000;
  updateSleepTimerUi();
  state.sleepTimerId = setInterval(() => {
    if (Date.now() >= state.sleepEndTimeMs) {
      triggerSleepPause();
    } else {
      updateSleepTimerUi();
    }
  }, 1000);
  showToast(`Таймер сна: ${minutes} мин`);
}

function startSleepTimerEndOfTrack() {
  cancelSleepTimer(true);
  state.sleepMode = "end_of_track";
  updateSleepTimerUi();
  showToast("Пауза после завершения трека");
}

/* PLAYBACK CONTROL */
async function playTrackWithQueue(track, queueList) {
  consecutiveTrackErrors = 0;
  if (queueList && queueList.length) {
    state.originalQueue = [...queueList];
    if (state.isShuffle) {
      const rest = state.originalQueue.filter((t) => t.id !== track.id);
      state.queue = [track, ...shuffleArray(rest)];
    } else {
      state.queue = [...state.originalQueue];
    }
  }
  state.currentIndex = state.queue.findIndex((t) => t.id === track.id);
  await startTrack(track);
}

function getNextQueueIndex() {
  if (!state.queue.length) return -1;
  const next = state.currentIndex + 1;
  if (next >= state.queue.length) {
    return state.repeatMode === "all" ? 0 : -1;
  }
  return next;
}

function resetPerTrackPreloadFlags() {
  state.crossfadeTriggeredForId = null;
  nativePreloadAttemptedForId = null;
  nativePreloadSuccessForId = null;
  nativePreloadTrackRef = null;
  nativeCrossfadeStartedForId = null;
  nativeEndTriggeredForId = null;
  webaudioGaplessPreloadedForId = null;
}

function applyNowPlayingMetadata(track, { recordHistory = true } = {}) {
  state.currentTrack = track;
  resetPerTrackPreloadFlags();

  if (recordHistory) {
    api.recordTrackPlayed(track.id).then((ids) => {
      state.recentlyPlayedIds = ids || state.recentlyPlayedIds;
    });
  }

  document.title = `${track.title} — ${track.artist} • Zuna`;
  updateBarNowPlaying();
  updateBackgroundLayers();
  syncMprisMediaSession(track);
  renderQueueDock();
  highlightActiveRowInDOM();
  fetchAndRenderLyrics(track);
}

function stopWebAudioDecks() {
  cancelActiveCrossfade(true);
  for (const d of decks) {
    try {
      d.el.pause();
      d.el.removeAttribute("src");
      d.el.load();
    } catch (_) {}
  }
}

async function seekToSeconds(sec) {
  if (!state.currentTrack) return;
  resetPerTrackPreloadFlags();
  const dur = getCurrentPlaybackDurationSec();
  const target = Math.max(0, dur > 0 ? Math.min(dur, sec) : sec);
  if (pendingResumeTrackId === state.currentTrack.id && !state.isPlaying) {
    pendingResumePositionSec = target;
    nativeStatusCache.positionSec = target;
    syncPlaybackAnchor(target);
  } else if (activeBackend === "native") {
    await api.nativeSeekSeconds(target);
    nativeStatusCache.positionSec = target;
    syncPlaybackAnchor(target);
  } else {
    getActiveAudio().currentTime = target;
  }
  const ratio = dur > 0 ? Math.min(1, target / dur) : 0;
  const sliderVal = Math.round(ratio * 1000);
  ["seek-bar", "fs-seek-bar"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) {
      el.value = sliderVal;
      updateSliderFill(el);
    }
  });
  const curStr = formatTime(target * 1000);
  document.getElementById("time-cur").textContent = curStr;
  document.getElementById("fs-time-cur").textContent = curStr;
  updateMprisPositionState(true);
  updateActiveLyricLine(true);
  persistPlaybackSnapshot(false);
}

async function startTrack(track, startAtSec = 0) {
  if (!track) return;
  pauseFadeToken++;
  state.isStopped = false;
  resetPerTrackPreloadFlags();
  pendingResumeTrackId = null;
  pendingResumePositionSec = 0;

  if (isNativeTrackSupported(track)) {
    stopWebAudioDecks();
    await api.nativeCancelCrossfade();
    await api.nativeCancelPauseFade();
    await api.nativeSetVolume(state.settings.volume ?? 0.85);
    await api.nativeSetReplayGain(Boolean(state.settings.replayGainEnabled));
    await api.nativeSetGapless(state.settings.gaplessEnabled !== false);
    await api.nativeUpdateEq(state.eqConfig);

    const loaded = await api.nativeLoadFile(track.path);
    if (loaded) {
      if (startAtSec > 0.1) {
        await api.nativeSeekSeconds(startAtSec);
      }
      const started = await api.nativeStart();
      if (started) {
        activeBackend = "native";
        try {
          const st = await api.nativeGetStatus();
          if (st) nativeStatusCache = st;
        } catch (_) {}
        const effectivePos = startAtSec > 0.1 ? startAtSec : 0;
        nativeStatusCache.positionSec = effectivePos;
        syncPlaybackAnchor(effectivePos);
        if (!(nativeStatusCache.durationSec > 0)) {
          nativeStatusCache.durationSec = (track.durationMs || 0) / 1000;
        }
        nativeStatusCache.isPlaying = true;
        nativeStatusCache.isEof = false;
        state.isPlaying = true;
        consecutiveTrackErrors = 0;
        applyNowPlayingMetadata(track);
        persistPlaybackSnapshot(false);
        return;
      }
    }
    if (state.settings.exclusiveMode) {
      try {
        const st = await api.nativeGetStatus();
        if (st) nativeStatusCache = st;
      } catch (_) {}
      activeBackend = "native";
      state.isPlaying = false;
      updateBarNowPlaying();
      showToast(nativeStatusCache.lastError || "Эксклюзивный режим: не удалось захватить ЦАП");
      if (state.currentView === "settings") renderMainWorkspace();
      return;
    }
  }

  // Fallback to WebAudio engine (OGG, M4A, OPUS, or if native failed to open)
  if (state.nativeAvailable) {
    await api.nativeStop();
  }
  activeBackend = "webaudio";
  ensureAudioGraph();
  cancelActiveCrossfade(true);

  const deck = getActiveDeck();
  const now = audioCtx.currentTime;
  if (envelopeNode) {
    envelopeNode.gain.cancelScheduledValues(now);
    envelopeNode.gain.setValueAtTime(getEnvelopeTargetGain(), now);
  }
  if (deck.gainNode) {
    deck.gainNode.gain.cancelScheduledValues(now);
    deck.gainNode.gain.setValueAtTime(1.0, now);
  }

  deck.el.src = toFileUrl(track.path);
  try {
    if (startAtSec > 0.1) {
      await new Promise((resolve) => {
        if (deck.el.readyState >= 1) {
          resolve();
          return;
        }
        const done = () => {
          deck.el.removeEventListener("loadedmetadata", done);
          deck.el.removeEventListener("error", done);
          resolve();
        };
        deck.el.addEventListener("loadedmetadata", done, { once: true });
        deck.el.addEventListener("error", done, { once: true });
      });
      try {
        deck.el.currentTime = startAtSec;
      } catch (_) {}
    }
    await deck.el.play();
    state.isPlaying = true;
    consecutiveTrackErrors = 0;
    applyNowPlayingMetadata(track);
    persistPlaybackSnapshot(false);
  } catch (err) {
    console.error("Audio play error:", err);
    handlePlaybackOpenFailure(track);
  }
}

function handlePlaybackOpenFailure(track) {
  consecutiveTrackErrors++;
  showToast(`Не удалось открыть: ${track?.title || "файл"}`);
  if (consecutiveTrackErrors >= Math.max(1, state.queue.length)) {
    state.isPlaying = false;
    updateBarNowPlaying();
    return;
  }
  const next = getNextQueueIndex();
  if (next >= 0) {
    state.currentIndex = next;
    startTrack(state.queue[next]);
  } else {
    state.isPlaying = false;
    updateBarNowPlaying();
  }
}

async function startCrossfadeToTrack(nextIdx, durationMs) {
  const nextTrack = state.queue[nextIdx];
  if (!nextTrack) return;

  if (activeBackend === "native") {
    await startTrack(nextTrack);
    return;
  }

  ensureAudioGraph();
  pauseFadeToken++;
  cancelActiveCrossfade(true);

  const fromIdx = activeDeckIdx;
  const toIdx = 1 - fromIdx;
  const fromDeck = decks[fromIdx];
  const toDeck = decks[toIdx];

  activeDeckIdx = toIdx;
  state.currentIndex = nextIdx;

  const now = audioCtx.currentTime;
  if (envelopeNode) {
    envelopeNode.gain.cancelScheduledValues(now);
    envelopeNode.gain.setValueAtTime(getEnvelopeTargetGain(), now);
  }
  if (toDeck.gainNode) {
    toDeck.gainNode.gain.cancelScheduledValues(now);
    toDeck.gainNode.gain.setValueAtTime(0.0001, now);
  }

  if (toDeck.el.src !== toFileUrl(nextTrack.path)) {
    toDeck.el.src = toFileUrl(nextTrack.path);
  }
  try {
    await toDeck.el.play();
    state.isPlaying = true;
  } catch (err) {
    console.error("Crossfade start error:", err);
    activeDeckIdx = fromIdx;
    return;
  }

  applyNowPlayingMetadata(nextTrack);

  const startTimeMs = performance.now();
  const totalMs = Math.max(300, durationMs);
  const timerId = setInterval(() => {
    const p = Math.min(1, Math.max(0, (performance.now() - startTimeMs) / totalMs));
    const t = audioCtx.currentTime;
    const outGain = Math.max(0.0001, Math.cos(p * Math.PI * 0.5));
    const inGain = Math.max(0.0001, Math.sin(p * Math.PI * 0.5));
    if (fromDeck.gainNode) fromDeck.gainNode.gain.setTargetAtTime(outGain, t, 0.015);
    if (toDeck.gainNode) toDeck.gainNode.gain.setTargetAtTime(inGain, t, 0.015);
    if (p >= 1) {
      cancelActiveCrossfade(true);
    }
  }, 25);

  crossfadeState = { fromIdx, toIdx, startTimeMs, durationMs: totalMs, timerId };
}

function highlightActiveRowInDOM() {
  document.querySelectorAll(".track-item[data-track-id]").forEach((el) => {
    el.classList.toggle("active", el.dataset.trackId === state.currentTrack?.id);
  });
}

function updateBarNowPlaying() {
  const t = state.currentTrack;
  const playMarkup = state.isPlaying
    ? `<rect x="6" y="4" width="4" height="16"></rect><rect x="14" y="4" width="4" height="16"></rect>`
    : `<polygon points="6 4 20 12 6 20 6 4"></polygon>`;

  document.getElementById("svg-play-icon").innerHTML = playMarkup;
  document.getElementById("svg-fs-play-icon").innerHTML = playMarkup;

  if ("mediaSession" in navigator) {
    navigator.mediaSession.playbackState = state.isPlaying ? "playing" : "paused";
  }
  pushNativeMprisState(false);

  if (!t) return;
  const badge = formatTrackBadge(t);
  const displayBadge = activeBackend === "native" && nativeStatusCache.exclusiveActual ? `${badge} • EXCL` : badge;
  const showBadges = Boolean(state.settings.showQualityBadges);

  // Bottom bar
  document.getElementById("bar-title").textContent = t.title;
  document.getElementById("bar-artist").textContent = `${t.artist} • ${t.album}`;
  const barTag = document.getElementById("bar-format-tag");
  barTag.textContent = displayBadge;
  barTag.classList.toggle("hidden", !showBadges);

  // Right Dock Now Playing Card
  document.getElementById("dock-np-title").textContent = t.title;
  document.getElementById("dock-np-artist").textContent = `${t.artist} • ${t.album}`;
  const dockSpecs = document.getElementById("dock-np-specs");
  dockSpecs.textContent = displayBadge;
  dockSpecs.classList.toggle("hidden", !showBadges);

  // Fullscreen Stage
  document.getElementById("fs-title").textContent = t.title;
  document.getElementById("fs-artist").textContent = t.artist;
  document.getElementById("fs-album").textContent = t.album;
  const fsQuality = document.getElementById("fs-quality-tag");
  if (fsQuality) {
    fsQuality.textContent = displayBadge;
    fsQuality.classList.toggle("hidden", !showBadges);
  }

  const isFav = state.favoriteIds.includes(t.id);
  document.getElementById("svg-bar-fav").setAttribute("fill", isFav ? "currentColor" : "none");
  document.getElementById("svg-fs-fav").setAttribute("fill", isFav ? "currentColor" : "none");

  const pairs = [
    ["bar-cover-img", "bar-cover-svg", false],
    ["dock-cover-img", "dock-cover-fallback", false],
    ["fs-cover-img", "fs-cover-svg", true],
  ];

  pairs.forEach(([imgId, fbId, preferFull]) => {
    const imgEl = document.getElementById(imgId);
    const fbEl = document.getElementById(fbId);
    const effCover = getEffectiveTrackCover(t, preferFull);
    if (effCover) {
      imgEl.src = toFileUrl(effCover);
      imgEl.classList.remove("hidden");
      fbEl.classList.add("hidden");
    } else {
      imgEl.classList.add("hidden");
      fbEl.classList.remove("hidden");
    }
  });
  if (typeof scheduleVisualizerDraw === "function") scheduleVisualizerDraw();
  if (t && !getEffectiveTrackCover(t, true)) {
    requestOnlineCoverForTrack(t, true);
  }
}

function renderQueueDock() {
  const lbl = document.getElementById("queue-count-label");
  if (lbl) lbl.textContent = `ОЧЕРЕДЬ: ${state.queue.length}`;
  const box = document.getElementById("queue-container");
  box.innerHTML = "";
  const slice = state.queue.slice(0, 150);
  slice.forEach((t, idx) => {
    const row = document.createElement("div");
    row.className = `track-item ${idx === state.currentIndex ? "active" : ""}`;
    row.style.padding = "6px 9px";
    row.innerHTML = `
      <div class="track-index">${idx + 1}</div>
      <div class="track-info">
        <div class="track-title" style="font-size:12px;">${escapeHtml(t.title)}</div>
        <div class="track-sub" style="font-size:10.5px;">${escapeHtml(t.artist)}</div>
      </div>
      <div class="track-time" style="font-size:10.5px;">${formatTime(t.durationMs)}</div>
      <button class="icon-btn q-rm-btn" title="Удалить из очереди" style="width:22px;height:22px;font-size:12px;">✕</button>
    `;
    row.onclick = (e) => {
      if (e.target.closest(".q-rm-btn")) {
        e.stopPropagation();
        state.queue.splice(idx, 1);
        if (idx < state.currentIndex) state.currentIndex--;
        else if (idx === state.currentIndex && state.currentIndex >= state.queue.length) {
          state.currentIndex = Math.max(0, state.queue.length - 1);
        }
        renderQueueDock();
        return;
      }
      state.currentIndex = idx;
      startTrack(t);
    };
    box.appendChild(row);
  });
}

/* APPLE MUSIC-STYLE INERTIAL LERP AUTOSCROLL FOR SYNCED LYRICS */
const activeScrollAnimations = new WeakMap();

function stopLyricsAutoScroll(container) {
  if (!container) return;
  const st = activeScrollAnimations.get(container);
  if (st && st.rafId) {
    cancelAnimationFrame(st.rafId);
  }
  activeScrollAnimations.delete(container);
}

function smoothScrollContainerTo(container, targetTop) {
  if (!container) return;
  const maxScroll = Math.max(0, container.scrollHeight - container.clientHeight);
  const clampedTarget = Math.max(0, Math.min(maxScroll, targetTop));

  let anim = activeScrollAnimations.get(container);
  if (anim) {
    anim.target = clampedTarget;
    return;
  }

  anim = { target: clampedTarget, pos: container.scrollTop, lastTs: performance.now(), rafId: null };
  activeScrollAnimations.set(container, anim);

  function step(now) {
    if (state.manualLyricsScroll) {
      activeScrollAnimations.delete(container);
      return;
    }
    const dt = Math.min(48, Math.max(4, now - anim.lastTs));
    anim.lastTs = now;
    // Frame-rate normalized exponential lerp (~0.105 at 60Hz) for silky Apple Music inertia
    const alpha = 1 - Math.pow(1 - 0.105, dt / 16.6667);
    const diff = anim.target - anim.pos;
    if (Math.abs(diff) < 0.45) {
      container.scrollTop = anim.target;
      activeScrollAnimations.delete(container);
      return;
    }
    anim.pos += diff * alpha;
    container.scrollTop = anim.pos;
    anim.rafId = requestAnimationFrame(step);
  }
  anim.rafId = requestAnimationFrame(step);
}

/* PER-TRACK LYRICS OFFSET / DELAY HELPERS */
function getCurrentTrackLyricOffsetMs() {
  const trackId = state.currentTrack?.id;
  if (!trackId || !state.lyricsOffsets) return 0;
  return Number(state.lyricsOffsets[trackId] || 0);
}

function formatLyricOffsetSec(offsetMs) {
  const sec = (offsetMs || 0) / 1000;
  if (Math.abs(sec) < 0.01) return "0.0s";
  return `${sec > 0 ? "+" : ""}${sec.toFixed(1)}s`;
}

function syncLyricOffsetControlsUI() {
  const hasSynced = Boolean(
    state.currentTrack && state.lyrics && state.lyrics.isSynced && state.lyrics.lines?.length
  );
  const dockBar = document.getElementById("dock-lyric-offset-bar");
  const fsBar = document.getElementById("fs-lyric-offset-bar");
  if (dockBar) dockBar.classList.toggle("hidden", !hasSynced);
  if (fsBar) fsBar.classList.toggle("hidden", !hasSynced);

  const offsetMs = getCurrentTrackLyricOffsetMs();
  const label = formatLyricOffsetSec(offsetMs);
  const isNonZero = Math.abs(offsetMs) >= 10;

  ["dock-lyric-offset-val", "fs-lyric-offset-val"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) {
      el.textContent = label;
      el.classList.toggle("active", isNonZero);
    }
  });
}

async function adjustCurrentTrackLyricOffset(deltaMs, reset = false) {
  const trackId = state.currentTrack?.id;
  if (!trackId) return;
  const cur = getCurrentTrackLyricOffsetMs();
  const next = reset ? 0 : Math.max(-30000, Math.min(30000, cur + deltaMs));
  if (!state.lyricsOffsets) state.lyricsOffsets = {};
  if (Math.abs(next) < 10) {
    delete state.lyricsOffsets[trackId];
  } else {
    state.lyricsOffsets[trackId] = next;
  }
  syncLyricOffsetControlsUI();
  updateActiveLyricLine(true);
  state.lyricsOffsets = await api.saveLyricOffset(trackId, next);
}

async function fetchAndRenderLyrics(track) {
  const dockBox = document.getElementById("lyrics-container");
  const fsBox = document.getElementById("fs-lyrics-container");
  const tagEl = document.getElementById("lyrics-status-tag");

  tagEl.textContent = "ПОИСК ТЕКСТА...";
  dockBox.innerHTML = `<div class="empty-note">Загрузка текста...</div>`;
  fsBox.innerHTML = `<div class="empty-note">Загрузка текста...</div>`;
  state.lyrics = null;
  state.activeLyricIndex = -2;
  syncLyricOffsetControlsUI();

  const data = await api.loadLyrics({
    path: track.path,
    title: track.title,
    artist: track.artist,
    album: track.album,
    durationMs: track.durationMs,
  });

  if (state.currentTrack?.id !== track.id) return;
  state.lyrics = data;
  mountLyricsDOM();
}

function mountLyricsDOM() {
  const dockBox = document.getElementById("lyrics-container");
  const fsBox = document.getElementById("fs-lyrics-container");
  const tagEl = document.getElementById("lyrics-status-tag");

  syncLyricOffsetControlsUI();

  if (!state.lyrics || (!state.lyrics.lines?.length && !state.lyrics.isInstrumental)) {
    tagEl.textContent = "ТЕКСТ НЕ НАЙДЕН";
    const msg = `<div class="empty-note">Текст не найден.<br/>Нажмите «.LRC» сверху, чтобы выбрать файл.</div>`;
    dockBox.innerHTML = msg;
    fsBox.innerHTML = msg;
    return;
  }

  if (state.lyrics.isInstrumental) {
    tagEl.textContent = "ИНСТРУМЕНТАЛ";
    const msg = `<div class="empty-note">Инструментальная композиция</div>`;
    dockBox.innerHTML = msg;
    fsBox.innerHTML = msg;
    return;
  }

  tagEl.textContent = state.lyrics.isSynced
    ? `${state.lyrics.hasWordSync ? "ПОСЛОВНО" : "СИНХРОНИЗИРОВАНО"} • ${state.lyrics.source}`
    : `БЕЗ СИНХРОНИЗАЦИИ • ${state.lyrics.source}`;

  const populate = (container) => {
    container.innerHTML = "";
    container.classList.toggle("unsynced", !state.lyrics.isSynced);
    const frag = document.createDocumentFragment();
    state.lyrics.lines.forEach((line) => {
      const div = document.createElement("div");
      div.className = "lyric-row";
      if (Array.isArray(line.words) && line.words.length > 0) {
        line.words.forEach((w) => {
          const span = document.createElement("span");
          span.className = "lyric-word";
          span.textContent = w.text;
          div.appendChild(span);
        });
      } else {
        div.textContent = line.text;
      }
      div.onclick = () => {
        if (state.lyrics.isSynced) {
          state.manualLyricsScroll = false;
          const offsetMs = getCurrentTrackLyricOffsetMs();
          seekToSeconds(Math.max(0, (line.timestampMs + offsetMs) / 1000));
        }
      };
      frag.appendChild(div);
    });
    container.appendChild(frag);
  };

  populate(dockBox);
  populate(fsBox);
  state.activeLyricIndex = -2;
  updateActiveLyricLine(true);
  ensureWordSyncRafLoop();
}

function updateWordHighlightFrame() {
  if (!state.lyrics || !state.lyrics.isSynced || !state.lyrics.hasWordSync) return;
  const idx = state.activeLyricIndex;
  if (idx < 0 || idx >= state.lyrics.lines.length) return;
  const line = state.lyrics.lines[idx];
  if (!line || !Array.isArray(line.words) || line.words.length === 0) return;

  const adjustedPosMs = getSmoothPlaybackTimeSec() * 1000 - getCurrentTrackLyricOffsetMs();

  [
    document.getElementById("lyrics-container"),
    document.getElementById("fs-lyrics-container"),
  ].forEach((container) => {
    if (!container) return;
    const row = container.children[idx];
    if (!row || !row.classList.contains("active")) return;
    const spans = row.children;
    for (let k = 0; k < line.words.length && k < spans.length; k++) {
      const w = line.words[k];
      const sp = spans[k];
      const dur = Math.max(40, (w.endMs || w.timestampMs + 250) - w.timestampMs);
      const rawP = (adjustedPosMs - w.timestampMs) / dur;
      const p = rawP <= 0 ? 0 : rawP >= 1 ? 1 : rawP;
      const pct = (p * 100).toFixed(1) + "%";
      const feather = p > 0 && p < 1 ? "8%" : "0%";
      sp.style.setProperty("--word-pct", pct);
      sp.style.setProperty("--word-feather", feather);
      sp.style.transform = p > 0.01 && p < 0.99 ? "translateY(-1.5px)" : "translateY(0px)";
    }
  });
}

function ensureWordSyncRafLoop() {
  if (wordSyncRafId) return;
  if (!state.lyrics || !state.lyrics.isSynced || !state.lyrics.hasWordSync) return;
  if (document.hidden || !state.isPlaying) return;
  const ps = getEffectivePowerSaver();
  const step = () => {
    wordSyncRafId = 0;
    if (!state.lyrics || !state.lyrics.isSynced || !state.lyrics.hasWordSync) return;
    if (document.hidden || !state.isPlaying) return;
    updateActiveLyricLine(false);
    if (!ps.reduceAnimations) updateWordHighlightFrame();
    if (state.isPlaying && !ps.limitUiRefresh) {
      wordSyncRafId = requestAnimationFrame(step);
    }
  };
  wordSyncRafId = requestAnimationFrame(step);
}

function updateActiveLyricLine(force = false) {
  if (!state.lyrics || !state.lyrics.isSynced || !state.lyrics.lines.length) return;
  if (document.hidden && !force) return;
  const ps = getEffectivePowerSaver();
  const posMs = getSmoothPlaybackTimeSec() * 1000;
  const offsetMs = getCurrentTrackLyricOffsetMs();
  const lines = state.lyrics.lines;

  let foundIdx = -1;
  for (let i = 0; i < lines.length; i++) {
    if (posMs >= lines[i].timestampMs + offsetMs) {
      foundIdx = i;
    } else {
      break;
    }
  }

  if (foundIdx === state.activeLyricIndex && !force) {
    if (state.lyrics.hasWordSync && !ps.reduceAnimations) updateWordHighlightFrame();
    return;
  }
  state.activeLyricIndex = foundIdx;

  const rawBlurScale = (state.settings.lyricsBlurIntensity ?? 100) / 100;
  const blurScale = (ps.disableBlur || ps.reduceAnimations) ? 0 : ps.isBalanced ? rawBlurScale * 0.45 : rawBlurScale;

  [
    document.getElementById("lyrics-container"),
    document.getElementById("fs-lyrics-container"),
  ].forEach((container, cIdx) => {
    if (!container) return;
    const rows = container.children;
    const mult = (cIdx === 1 ? 1.25 : 1.0) * blurScale;

    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      const isActive = i === foundIdx;
      row.classList.toggle("active", isActive);

      if (foundIdx < 0) {
        row.style.filter = "blur(0px)";
        row.style.opacity = "0.52";
        row.style.transform = ps.reduceAnimations ? "none" : "scale(0.92)";
        continue;
      }

      const dist = Math.abs(i - foundIdx);
      let blurPx = 0;
      let alpha = 1.0;
      let scale = 1.0;

      if (dist === 0) {
        blurPx = 0;
        alpha = 1.0;
        scale = 1.0;
      } else if (dist === 1) {
        blurPx = 1.4 * mult;
        alpha = 0.48;
        scale = 0.92;
      } else if (dist === 2) {
        blurPx = 3.0 * mult;
        alpha = 0.34;
        scale = 0.9;
      } else if (dist === 3) {
        blurPx = 4.8 * mult;
        alpha = 0.24;
        scale = 0.88;
      } else {
        blurPx = Math.min(8.5 * mult, (4.8 + (dist - 3) * 1.2) * mult);
        alpha = Math.max(0.14, 0.24 - (dist - 3) * 0.03);
        scale = 0.86;
      }

      row.style.filter = blurPx > 0.05 ? `blur(${blurPx.toFixed(1)}px)` : "none";
      row.style.opacity = alpha.toFixed(2);
      row.style.transform = ps.reduceAnimations ? "none" : `scale(${scale})`;
    }

    if (!state.manualLyricsScroll && foundIdx >= 0 && rows[foundIdx]) {
      const activeEl = rows[foundIdx];
      const targetTop =
        activeEl.offsetTop - container.clientHeight / 2 + activeEl.clientHeight / 2;
      if (ps.reduceAnimations) {
        container.scrollTop = Math.max(0, targetTop);
      } else {
        smoothScrollContainerTo(container, targetTop);
      }
    }
  });
  if (state.lyrics.hasWordSync && !ps.reduceAnimations) {
    updateWordHighlightFrame();
    if (state.isPlaying && !wordSyncRafId) ensureWordSyncRafLoop();
  }
}

async function stopPlayback() {
  pauseFadeToken++;
  cancelActiveCrossfade(true);
  resetPerTrackPreloadFlags();
  if (state.nativeAvailable) {
    try { await api.nativeCancelPauseFade(); } catch (_) {}
    try { await api.nativeCancelCrossfade(); } catch (_) {}
    try { await api.nativeStop(); } catch (_) {}
  }
  stopWebAudioDecks();
  nativeStatusCache.positionSec = 0;
  nativeStatusCache.isPlaying = false;
  nativeStatusCache.isEof = false;
  syncPlaybackAnchor(0);
  state.isPlaying = false;
  state.isStopped = Boolean(state.currentTrack);
  state.activeLyricIndex = -2;
  pendingResumeTrackId = state.currentTrack?.id || null;
  pendingResumePositionSec = 0;

  ["seek-bar", "fs-seek-bar"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) {
      el.value = 0;
      updateSliderFill(el);
    }
  });
  ["time-cur", "fs-time-cur"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.textContent = "0:00";
  });
  if (state.currentTrack) {
    const durStr = formatTime(state.currentTrack.durationMs || 0);
    ["time-dur", "fs-time-dur"].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.textContent = durStr;
    });
  }
  updateBarNowPlaying();
  updateActiveLyricLine(true);
  pushNativeMprisState(true);
  if ("mediaSession" in navigator) {
    try { navigator.mediaSession.playbackState = "none"; } catch (_) {}
  }
  persistPlaybackSnapshot(true);
}

async function togglePlayPause() {
  const visTracks = getVisibleTracks();
  if (!state.currentTrack) {
    if (visTracks.length > 0) {
      playTrackWithQueue(visTracks[0], visTracks);
    }
    return;
  }
  state.isStopped = false;
  if (!state.isPlaying && pendingResumeTrackId === state.currentTrack.id) {
    await startTrack(state.currentTrack, pendingResumePositionSec);
    return;
  }
  const token = ++pauseFadeToken;

  if (activeBackend === "native") {
    if (!state.isPlaying) {
      await api.nativeCancelPauseFade();
      const ok = await api.nativeStart();
      if (ok) {
        syncPlaybackAnchor(nativeStatusCache.positionSec || 0);
        state.isPlaying = true;
        updateBarNowPlaying();
        ensureWordSyncRafLoop();
      }
    } else {
      state.isPlaying = false;
      updateBarNowPlaying();
      persistPlaybackSnapshot(true);
      await api.nativeBeginPauseFade(110);
      setTimeout(() => {
        if (pauseFadeToken === token && !state.isPlaying && activeBackend === "native") {
          api.nativePause();
        }
      }, 115);
    }
    return;
  }

  ensureAudioGraph();
  const audioEl = getActiveAudio();
  const targetGain = getEnvelopeTargetGain();
  const now = audioCtx.currentTime;

  if (audioEl.paused || !state.isPlaying) {
    if (envelopeNode) {
      envelopeNode.gain.cancelScheduledValues(now);
      envelopeNode.gain.setValueAtTime(0.0001, now);
      envelopeNode.gain.linearRampToValueAtTime(targetGain, now + 0.045);
    }
    audioEl.play().catch(() => {});
    state.isPlaying = true;
    updateBarNowPlaying();
  } else {
    state.isPlaying = false;
    updateBarNowPlaying();
    cancelActiveCrossfade(true);
    if (envelopeNode) {
      envelopeNode.gain.cancelScheduledValues(now);
      envelopeNode.gain.setValueAtTime(Math.max(0.0001, envelopeNode.gain.value || targetGain), now);
      envelopeNode.gain.linearRampToValueAtTime(0.0001, now + 0.11);
      setTimeout(() => {
        if (pauseFadeToken === token && !state.isPlaying && activeBackend === "webaudio") {
          audioEl.pause();
        }
      }, 115);
    } else {
      audioEl.pause();
    }
  }
}

async function playNextTrack(fromAutoEnd = false) {
  if (!state.queue.length) return;
  if (fromAutoEnd && state.sleepMode === "end_of_track") {
    cancelSleepTimer(true);
    state.isPlaying = false;
    if (activeBackend === "native") await api.nativePause();
    updateBarNowPlaying();
    showToast("Таймер сна: трек завершён, пауза", 3200);
    return;
  }
  if (state.repeatMode === "one") {
    await seekToSeconds(0);
    if (activeBackend === "native") await api.nativeStart();
    else getActiveAudio().play().catch(() => {});
    state.isPlaying = true;
    updateBarNowPlaying();
    return;
  }
  const next = getNextQueueIndex();
  if (next < 0) {
    if (activeBackend === "native") await api.nativePause();
    cancelActiveCrossfade(true);
    state.isPlaying = false;
    updateBarNowPlaying();
    return;
  }
  state.currentIndex = next;
  await startTrack(state.queue[next]);
}

async function playPrevTrack() {
  if (!state.queue.length) return;
  if (getCurrentPlaybackTimeSec() > 3.5) {
    await seekToSeconds(0);
    return;
  }
  let prev = state.currentIndex - 1;
  if (prev < 0) prev = state.queue.length - 1;
  state.currentIndex = prev;
  await startTrack(state.queue[prev]);
}

function updateTransportState() {
  document.getElementById("btn-shuffle").classList.toggle("active", state.isShuffle);
  document.getElementById("btn-fs-shuffle").classList.toggle("active", state.isShuffle);
  document.getElementById("btn-repeat").classList.toggle("active", state.repeatMode !== "off");
  document.getElementById("btn-fs-repeat").classList.toggle("active", state.repeatMode !== "off");
  pushNativeMprisState(false);
}

async function handlePickFolder() {
  const updated = await api.pickAndScanFolder();
  if (updated) {
    state.folders = updated.folders;
    state.tracks = updated.tracks;
    updateCountsAndBadges();
    renderMainWorkspace();
    showToast(`Добавлено • ${state.tracks.length} треков`);
  }
}

let rescanInProgress = false;
async function handleRescanLibrary() {
  if (rescanInProgress) return;
  rescanInProgress = true;
  showToast("Пересканирование медиатеки...", 1800);
  try {
    const res = await api.rescanFolders();
    if (res && res.tracks) {
      state.folders = res.folders || state.folders;
      state.tracks = res.tracks;
      updateCountsAndBadges();
      renderMainWorkspace();
      showToast(`Медиатека обновлена: ${getVisibleTracks().length} треков`);
    }
  } finally {
    rescanInProgress = false;
  }
}

/* CONFIGURABLE SPECTRUM VISUALIZER (BARS / WAVE / MIRROR — HYBRID NATIVE C++ & WEBAUDIO) */
function startSpectrumLoop() {
  const canvas = document.getElementById("mini-spectrum");
  const ctx = canvas.getContext("2d");
  const freqData = new Uint8Array(64);
  const timeData = new Uint8Array(64);
  let rafId = 0;

  function drawStaticIdle() {
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = getComputedStyle(document.documentElement).getPropertyValue("--line-strong").trim() || "#262626";
    for (let i = 0; i < 14; i++) {
      ctx.fillRect(i * 6 + 1, h - 2, 4, 2);
    }
  }

  function draw() {
    rafId = 0;
    const ps = getEffectivePowerSaver();
    if (!state.settings.visualizerEnabled || ps.disableVisualizer || document.hidden) {
      drawStaticIdle();
      return;
    }

    const hasSource = state.isPlaying && (activeBackend === "native" || Boolean(analyserNode));
    if (!hasSource) {
      drawStaticIdle();
      return;
    }

    rafId = requestAnimationFrame(draw);

    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const style = state.settings.visualizerStyle || "bars";
    const accColor = getAccentCssColor();
    ctx.fillStyle = accColor;
    ctx.strokeStyle = accColor;

    if (activeBackend === "native") {
      const bins = nativeStatusCache.spectrum || [];
      if (style === "wave") {
        ctx.lineWidth = 1.8;
        ctx.beginPath();
        const phase = performance.now() * 0.012;
        for (let i = 0; i < 32; i++) {
          const x = (i / 31) * w;
          const b = bins[Math.min(15, Math.floor(i / 2))] || 0;
          const y = h / 2 + Math.sin(i * 0.65 + phase) * b * (h * 0.44);
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        }
        ctx.stroke();
      } else {
        for (let i = 0; i < 14; i++) {
          const v = Math.max(0, Math.min(1, bins[i] || 0));
          const barH = Math.max(2, Math.round(v * (h - 2)));
          const y = style === "mirror" ? Math.round((h - barH) / 2) : h - barH;
          ctx.fillRect(i * 6 + 1, y, 4, barH);
        }
      }
      return;
    }

    if (style === "wave") {
      analyserNode.getByteTimeDomainData(timeData);
      ctx.lineWidth = 1.8;
      ctx.beginPath();
      for (let i = 0; i < timeData.length; i++) {
        const x = (i / (timeData.length - 1)) * w;
        const v = (timeData[i] - 128) / 128;
        const y = h / 2 + v * (h * 0.45);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
    } else if (style === "mirror") {
      analyserNode.getByteFrequencyData(freqData);
      for (let i = 0; i < 14; i++) {
        const v = freqData[i + 1] / 255;
        const barH = Math.max(2, Math.round(v * (h - 2)));
        const y = Math.round((h - barH) / 2);
        ctx.fillRect(i * 6 + 1, y, 4, barH);
      }
    } else {
      analyserNode.getByteFrequencyData(freqData);
      for (let i = 0; i < 14; i++) {
        const v = freqData[i + 1] / 255;
        const barH = Math.max(2, Math.round(v * (h - 2)));
        ctx.fillRect(i * 6 + 1, h - barH, 4, barH);
      }
    }
  }
  scheduleVisualizerDraw = () => {
    if (!rafId) rafId = requestAnimationFrame(draw);
  };
  scheduleVisualizerDraw();
}

window.addEventListener("DOMContentLoaded", async () => {
  const init = await api.getInitialState();
  state.folders = init.folders || [];
  state.tracks = init.tracks || [];
  state.favoriteIds = init.favoriteIds || [];
  state.recentlyPlayedIds = init.recentlyPlayedIds || [];
  state.lyricsOffsets = init.lyricsOffsets || {};
  state.playlists = init.playlists || [];
  state.eqConfig = init.eqConfig || state.eqConfig;
  state.settings = { ...state.settings, ...(init.settings || {}) };
  try {
    state.nativeAvailable = await api.nativeIsAvailable();
  } catch (_) {
    state.nativeAvailable = false;
  }

  const volPct = Math.round((state.settings.volume ?? 0.85) * 100);
  const volBar = document.getElementById("vol-bar");
  const fsVolBar = document.getElementById("fs-vol-bar");
  volBar.value = volPct;
  fsVolBar.value = volPct;
  updateSliderFill(volBar);
  updateSliderFill(fsVolBar);
  document.getElementById("vol-num").textContent = `${volPct}%`;
  for (const d of decks) d.el.volume = state.settings.volume ?? 0.85;

  const rightDock = document.getElementById("right-dock");
  const btnToggleDock = document.getElementById("btn-toggle-dock");
  const setRightDockOpen = (isOpen, persist = true) => {
    state.settings.rightDockOpen = Boolean(isOpen);
    rightDock.classList.toggle("collapsed", !isOpen);
    btnToggleDock.classList.toggle("active", Boolean(isOpen));
    if (isOpen && state.settings.rightDockWidth) {
      rightDock.style.width = `${state.settings.rightDockWidth}px`;
    }
    if (persist) api.saveSettings(state.settings);
  };

  if (state.settings.rightDockWidth) {
    rightDock.style.width = `${state.settings.rightDockWidth}px`;
  }
  setRightDockOpen(state.settings.rightDockOpen !== false, false);

  if (state.settings.outputDeviceId && state.settings.outputDeviceId !== "default") {
    for (const d of decks) {
      if (typeof d.el.setSinkId === "function") {
        d.el.setSinkId(state.settings.outputDeviceId).catch(() => {});
      }
    }
  }

  applyAppearanceSettings();
  renderSidebarPlaylists();
  renderMainWorkspace();
  restoreLastPlaybackState(init.lastPlayback);
  startSpectrumLoop();

  // Monitor battery level & visibility to optimize idle GPU/CPU without touching audio DSP
  if (typeof navigator.getBattery === "function") {
    navigator.getBattery().then((bat) => {
      const syncBattery = () => {
        state.batteryInfoKnown = true;
        state.batteryLevelPct = Math.round((bat.level ?? 1) * 100);
        state.batteryCharging = Boolean(bat.charging);
        const low = !bat.charging && (bat.level ?? 1) <= 0.2;
        const changed = low !== state.systemBatteryLow;
        state.systemBatteryLow = low;
        if (changed) {
          applyAppearanceSettings();
          if (state.currentView === "settings") renderMainWorkspace();
        }
      };
      syncBattery();
      bat.addEventListener("levelchange", syncBattery);
      bat.addEventListener("chargingchange", syncBattery);
    }).catch(() => {});
  }
  document.addEventListener("visibilitychange", () => {
    updateBackgroundLayers();
    if (!document.hidden && state.isPlaying) {
      ensureWordSyncRafLoop();
      updateActiveLyricLine(true);
    }
    if (typeof scheduleVisualizerDraw === "function") scheduleVisualizerDraw();
  });

  // Support drag & drop .zunatheme / .flactheme anywhere into the window to apply a friend's theme
  document.addEventListener("dragover", (e) => {
    e.preventDefault();
  });
  document.addEventListener("drop", async (e) => {
    e.preventDefault();
    const file = e.dataTransfer?.files?.[0];
    const lowerName = file?.name?.toLowerCase() || "";
    if (file && file.path && (lowerName.endsWith(".zunatheme") || lowerName.endsWith(".flactheme"))) {
      const res = await api.importTheme(file.path);
      if (res && res.error) {
        showToast(`Ошибка темы: ${res.error}`);
      } else if (res && res.settings) {
        state.settings = { ...state.settings, ...res.settings };
        applyAppearanceSettings();
        if (state.currentView === "settings") renderMainWorkspace();
        showToast(`Тема «${res.name || "ZunaTheme"}» применена`);
      }
    }
  });

  // Register MPRIS / MediaSession Action Handlers for Linux / Noctalia
  if ("mediaSession" in navigator) {
    try {
      navigator.mediaSession.setActionHandler("play", () => {
        if (!state.isPlaying) togglePlayPause();
      });
      navigator.mediaSession.setActionHandler("pause", () => {
        if (state.isPlaying) togglePlayPause();
      });
      navigator.mediaSession.setActionHandler("previoustrack", () => playPrevTrack());
      navigator.mediaSession.setActionHandler("nexttrack", () => playNextTrack());
      navigator.mediaSession.setActionHandler("seekto", (details) => {
        if (Number.isFinite(details.seekTime)) {
          seekToSeconds(details.seekTime);
        }
      });
      navigator.mediaSession.setActionHandler("seekbackward", (details) => {
        const offset = details.seekOffset || 10;
        seekToSeconds(Math.max(0, getCurrentPlaybackTimeSec() - offset));
      });
      navigator.mediaSession.setActionHandler("seekforward", (details) => {
        const offset = details.seekOffset || 10;
        seekToSeconds(getCurrentPlaybackTimeSec() + offset);
      });
    } catch (_) {}
  }

  window.addEventListener("click", () => {
    document.getElementById("playlist-popover").classList.add("hidden");
    document.getElementById("track-menu-popover").classList.add("hidden");
  });

  document.getElementById("btn-nav-back").onclick = navigateBack;
  document.getElementById("btn-topbar-rescan").onclick = handleRescanLibrary;
  document.getElementById("btn-clear-queue").onclick = async () => {
    if (state.currentTrack && state.queue.length > 0) {
      state.queue = [state.currentTrack];
      state.originalQueue = [state.currentTrack];
      state.currentIndex = 0;
    } else {
      state.queue = [];
      state.originalQueue = [];
      state.currentIndex = -1;
    }
    if (state.nativeAvailable) await api.nativeCancelCrossfade();
    renderQueueDock();
    showToast("Очередь очищена");
  };

  // Left Sidebar Toggle to Compact Icon Rail Mode (68px) on Logo Click
  document.getElementById("brand-toggle").onclick = () => {
    state.settings.sidebarCompact = !state.settings.sidebarCompact;
    updateSidebarCompactState();
    api.saveSettings(state.settings);
  };

  // Right Dock Toggle, Dock-Header Collapse Button & Drag-Resize
  btnToggleDock.onclick = () => {
    setRightDockOpen(rightDock.classList.contains("collapsed"), true);
  };
  document.getElementById("btn-hide-dock").onclick = () => {
    setRightDockOpen(false, true);
  };

  const resizer = document.getElementById("dock-resizer");
  let isResizingDock = false;
  resizer.addEventListener("mousedown", (e) => {
    e.preventDefault();
    isResizingDock = true;
    resizer.classList.add("dragging");
  });
  window.addEventListener("mousemove", (e) => {
    if (!isResizingDock) return;
    const rawWidth = window.innerWidth - e.clientX;
    if (rawWidth < 185) {
      setRightDockOpen(false, false);
      return;
    }
    if (rightDock.classList.contains("collapsed")) {
      rightDock.classList.remove("collapsed");
      btnToggleDock.classList.add("active");
      state.settings.rightDockOpen = true;
    }
    const newWidth = Math.max(250, Math.min(600, rawWidth));
    rightDock.style.width = `${newWidth}px`;
    state.settings.rightDockWidth = newWidth;
  });
  window.addEventListener("mouseup", () => {
    if (isResizingDock) {
      isResizingDock = false;
      resizer.classList.remove("dragging");
      api.saveSettings(state.settings);
    }
  });

  // Sidebar navigation
  document.querySelectorAll(".sidebar .nav-link[data-view]").forEach((btn) => {
    btn.onclick = () => {
      state.navHistory = [];
      navigateToView(btn.dataset.view, false);
    };
  });
  document.getElementById("btn-sidebar-add-folder").onclick = handlePickFolder;

  // Search input
  const searchInput = document.getElementById("search-input");
  const clearBtn = document.getElementById("btn-clear-search");
  searchInput.oninput = (e) => {
    state.searchQuery = e.target.value;
    clearBtn.classList.toggle("hidden", !state.searchQuery);
    if (state.currentView === "home" && state.searchQuery.trim()) {
      navigateToView("tracks", true);
    } else {
      renderMainWorkspace();
    }
  };
  clearBtn.onclick = () => {
    searchInput.value = "";
    state.searchQuery = "";
    clearBtn.classList.add("hidden");
    renderMainWorkspace();
  };

  // Right dock tabs
  const dockLyrics = document.getElementById("dock-lyrics-pane");
  const dockQueue = document.getElementById("dock-queue-pane");
  const tabLyrics = document.getElementById("tab-dock-lyrics");
  const tabQueue = document.getElementById("tab-dock-queue");

  const setDockMode = (mode) => {
    const isLyrics = mode === "lyrics";
    dockLyrics.classList.toggle("hidden", !isLyrics);
    dockQueue.classList.toggle("hidden", isLyrics);
    tabLyrics.classList.toggle("active", isLyrics);
    tabQueue.classList.toggle("active", !isLyrics);
  };
  tabLyrics.onclick = () => setDockMode("lyrics");
  tabQueue.onclick = () => setDockMode("queue");

  document.getElementById("btn-manual-lrc").onclick = async () => {
    const manual = await api.pickLrcFile();
    if (manual) {
      state.lyrics = manual;
      mountLyricsDOM();
    }
  };

  // Per-track synced lyrics delay / offset buttons (Right Dock & Fullscreen)
  document.querySelectorAll("[data-lyric-step]").forEach((btn) => {
    btn.onclick = (e) => {
      e.stopPropagation();
      const stepMs = parseInt(btn.dataset.lyricStep, 10) || 0;
      adjustCurrentTrackLyricOffset(stepMs, false);
    };
  });
  document.querySelectorAll("[data-lyric-reset]").forEach((btn) => {
    btn.onclick = (e) => {
      e.stopPropagation();
      adjustCurrentTrackLyricOffset(0, true);
    };
  });

  const lyricsContainers = [
    document.getElementById("lyrics-container"),
    document.getElementById("fs-lyrics-container"),
  ];
  const resumeBtns = [
    document.getElementById("btn-dock-lyrics-resume"),
    document.getElementById("btn-fs-lyrics-resume"),
  ];
  const setManualLyricsMode = (manual) => {
    state.manualLyricsScroll = manual;
    lyricsContainers.forEach((c) => {
      if (!c) return;
      c.classList.toggle("manual-scroll", manual);
      if (manual) stopLyricsAutoScroll(c);
    });
    resumeBtns.forEach((b) => {
      if (b) b.classList.toggle("hidden", !manual);
    });
    clearTimeout(state.manualScrollTimer);
    if (manual) {
      state.manualScrollTimer = setTimeout(() => {
        setManualLyricsMode(false);
        updateActiveLyricLine(true);
      }, 2800);
    }
  };
  resumeBtns.forEach((b) => {
    if (b) {
      b.onclick = () => {
        setManualLyricsMode(false);
        updateActiveLyricLine(true);
      };
    }
  });
  lyricsContainers.forEach((box) => {
    if (!box) return;
    box.addEventListener("wheel", () => setManualLyricsMode(true), { passive: true });
    box.addEventListener("touchstart", () => setManualLyricsMode(true), { passive: true });
  });

  // Slide-up Fullscreen Player from bottom mini-player
  const openFullscreen = () => {
    document.body.classList.add("fs-open");
    updateBackgroundLayers();
    setTimeout(() => updateActiveLyricLine(true), 80);
  };
  const closeFullscreen = () => {
    document.body.classList.remove("fs-open");
    updateBackgroundLayers();
  };

  document.getElementById("btn-open-fullscreen").onclick = openFullscreen;
  document.getElementById("bar-cover").onclick = openFullscreen;
  document.getElementById("dock-cover-box").onclick = openFullscreen;
  document.getElementById("btn-close-fs").onclick = closeFullscreen;

  // Clicking on the background of the bottom mini-player bar opens Fullscreen Player
  document.getElementById("bottom-player-bar").addEventListener("click", (e) => {
    if (e.target.closest(".no-expand") || e.target.closest("button") || e.target.closest("input")) {
      return;
    }
    openFullscreen();
  });

  // Transport controls (Bottom bar & Fullscreen)
  document.getElementById("btn-play-pause").onclick = togglePlayPause;
  document.getElementById("btn-fs-play").onclick = togglePlayPause;
  document.getElementById("btn-stop").onclick = stopPlayback;
  document.getElementById("btn-fs-stop").onclick = stopPlayback;
  document.getElementById("btn-next").onclick = playNextTrack;
  document.getElementById("btn-fs-next").onclick = playNextTrack;
  document.getElementById("btn-prev").onclick = playPrevTrack;
  document.getElementById("btn-fs-prev").onclick = playPrevTrack;

  document.getElementById("btn-fs-back-10").onclick = () => {
    seekToSeconds(Math.max(0, getCurrentPlaybackTimeSec() - 10));
  };
  document.getElementById("btn-fs-fwd-10").onclick = () => {
    seekToSeconds(getCurrentPlaybackTimeSec() + 10);
  };
  document.getElementById("btn-fs-playlist").onclick = (e) => {
    e.stopPropagation();
    if (state.currentTrack) openPlaylistPopover(e.currentTarget, state.currentTrack.id);
  };
  document.getElementById("btn-fs-info").onclick = () => {
    if (state.currentTrack) openTrackInfoModal(state.currentTrack);
  };

  const sleepModal = document.getElementById("sleep-modal");
  const openSleepModal = () => {
    updateSleepTimerUi();
    sleepModal.classList.remove("hidden");
  };
  document.getElementById("btn-sleep-timer").onclick = openSleepModal;
  document.getElementById("btn-fs-sleep").onclick = openSleepModal;
  document.getElementById("btn-close-sleep").onclick = () => sleepModal.classList.add("hidden");
  document.getElementById("btn-sleep-off").onclick = () => {
    cancelSleepTimer();
    sleepModal.classList.add("hidden");
  };
  document.getElementById("btn-sleep-end-track").onclick = () => {
    startSleepTimerEndOfTrack();
    sleepModal.classList.add("hidden");
  };
  document.querySelectorAll(".sleep-min-btn").forEach((b) => {
    b.onclick = () => {
      startSleepTimerMinutes(parseInt(b.dataset.sleepMin, 10) || 15);
      sleepModal.classList.add("hidden");
    };
  });

  document.getElementById("btn-close-track-info").onclick = () => {
    document.getElementById("track-info-modal").classList.add("hidden");
  };
  const expModal = document.getElementById("export-theme-modal");
  const expInput = document.getElementById("export-theme-name");
  document.getElementById("btn-cancel-export-theme").onclick = () => expModal.classList.add("hidden");
  document.getElementById("btn-confirm-export-theme").onclick = async () => {
    const themeName = expInput.value.trim() || "ZunaTheme";
    expModal.classList.add("hidden");
    const res = await api.exportTheme(themeName);
    if (res && !res.canceled) {
      showToast(res.error ? `Ошибка: ${res.error}` : `Тема «${themeName}» сохранена`);
    }
  };

  const toggleShuffle = () => {
    state.isShuffle = !state.isShuffle;
    if (state.queue.length > 1) {
      const cur = state.currentTrack;
      if (state.isShuffle) {
        if (!state.originalQueue.length) state.originalQueue = [...state.queue];
        const rest = state.queue.filter((t) => !cur || t.id !== cur.id);
        state.queue = cur ? [cur, ...shuffleArray(rest)] : shuffleArray(rest);
        state.currentIndex = cur ? 0 : -1;
      } else if (state.originalQueue.length) {
        state.queue = [...state.originalQueue];
        state.currentIndex = cur ? Math.max(0, state.queue.findIndex((t) => t.id === cur.id)) : 0;
      }
      renderQueueDock();
    }
    updateTransportState();
  };
  const toggleRepeat = () => {
    state.repeatMode =
      state.repeatMode === "off" ? "all" : state.repeatMode === "all" ? "one" : "off";
    updateTransportState();
  };
  document.getElementById("btn-shuffle").onclick = toggleShuffle;
  document.getElementById("btn-fs-shuffle").onclick = toggleShuffle;
  document.getElementById("btn-repeat").onclick = toggleRepeat;
  document.getElementById("btn-fs-repeat").onclick = toggleRepeat;

  const toggleCurrentFav = async () => {
    if (!state.currentTrack) return;
    state.favoriteIds = await api.toggleFavorite(state.currentTrack.id);
    updateCountsAndBadges();
    updateBarNowPlaying();
    if (["tracks", "favorites"].includes(state.currentView)) renderMainWorkspace();
  };
  document.getElementById("btn-bar-fav").onclick = toggleCurrentFav;
  document.getElementById("btn-fs-fav").onclick = toggleCurrentFav;

  // Seek bars (Bottom Bar & Fullscreen)
  const seekBar = document.getElementById("seek-bar");
  const fsSeekBar = document.getElementById("fs-seek-bar");
  updateSliderFill(seekBar);
  updateSliderFill(fsSeekBar);

  [seekBar, fsSeekBar].forEach((bar) => {
    bar.onmousedown = () => {
      state.isSeeking = true;
    };
    bar.oninput = (e) => {
      updateSliderFill(bar);
      const durMs = getCurrentPlaybackDurationSec() * 1000;
      const previewMs = (parseFloat(e.target.value) / 1000) * durMs;
      document.getElementById("time-cur").textContent = formatTime(previewMs);
      document.getElementById("fs-time-cur").textContent = formatTime(previewMs);
    };
    bar.onchange = async (e) => {
      const durSec = getCurrentPlaybackDurationSec();
      if (durSec > 0) {
        await seekToSeconds((parseFloat(e.target.value) / 1000) * durSec);
      }
      state.isSeeking = false;
    };
  });

  const renderProgressFrame = (curMs, durMs) => {
    if (!state.isSeeking) {
      const ratio = durMs > 0 ? Math.min(1, curMs / durMs) : 0;
      const sliderVal = Math.round(ratio * 1000);
      seekBar.value = sliderVal;
      fsSeekBar.value = sliderVal;
      updateSliderFill(seekBar);
      updateSliderFill(fsSeekBar);
      const curStr = formatTime(curMs);
      const durStr = formatTime(durMs);
      document.getElementById("time-cur").textContent = curStr;
      document.getElementById("time-dur").textContent = durStr;
      document.getElementById("fs-time-cur").textContent = curStr;
      document.getElementById("fs-time-dur").textContent = durStr;
    }
    updateMprisPositionState();
    updateActiveLyricLine(false);
    if (state.isPlaying && Date.now() - lastPlaybackPersistMs > 2000) {
      persistPlaybackSnapshot(false);
    }
  };

  let tickBusy = false;
  let lastProgressRenderAtMs = 0;
  const runPlaybackTick = async () => {
    let nextDelayMs = 450;
    if (state.isPlaying && state.currentTrack && !tickBusy) {
      tickBusy = true;
      try {
        const ps = getEffectivePowerSaver();
        const cfMs = (state.settings.crossfadeSeconds || 0) * 1000;
        let nearTransition = false;
        const shouldRenderUi =
          !document.hidden &&
          (!ps.limitUiRefresh ||
            Date.now() - lastProgressRenderAtMs >= (ps.mode === "balanced" ? 240 : 420));

        if (activeBackend === "native") {
          const st = await api.nativeGetStatus();
          if (st) {
            nativeStatusCache = st;
            syncPlaybackAnchor(st.positionSec || 0);
            const curMs = (st.positionSec || 0) * 1000;
            const durMs = st.durationSec > 0 ? st.durationSec * 1000 : state.currentTrack?.durationMs || 0;
            const remMs = durMs - curMs;
            nearTransition = durMs > 0 && remMs <= cfMs + 4500;

            if (shouldRenderUi) {
              renderProgressFrame(curMs, durMs);
              lastProgressRenderAtMs = Date.now();
            } else {
              updateMprisPositionState();
            }

            const curId = state.currentTrack?.id;
            const nextIdx = getNextQueueIndex();
            const nextTrack = nextIdx >= 0 ? state.queue[nextIdx] : null;
            const canPreloadNative =
              nextTrack &&
              state.repeatMode !== "one" &&
              state.sleepMode !== "end_of_track" &&
              isNativeTrackSupported(nextTrack) &&
              (cfMs > 0 || state.settings.gaplessEnabled !== false);

            if (canPreloadNative && durMs > 0 && remMs <= cfMs + 3500 && nativePreloadAttemptedForId !== curId) {
              nativePreloadAttemptedForId = curId;
              const ok = await api.nativePreloadNext(nextTrack.path);
              if (ok) {
                nativePreloadSuccessForId = curId;
                nativePreloadTrackRef = { track: nextTrack, idx: nextIdx };
              }
            }

            if (
              cfMs > 0 &&
              durMs > cfMs * 2.2 &&
              remMs <= cfMs &&
              remMs > 180 &&
              nativePreloadSuccessForId === curId &&
              nativeCrossfadeStartedForId !== curId
            ) {
              nativeCrossfadeStartedForId = curId;
              await api.nativeStartCrossfade(Math.min(cfMs, remMs));
            }

            if (st.crossfadeCompleted && nativePreloadTrackRef) {
              const promoted = await api.nativePromotePreloaded();
              if (promoted) {
                const { track: nTrack, idx: nIdx } = nativePreloadTrackRef;
                state.currentIndex = nIdx;
                applyNowPlayingMetadata(nTrack);
              }
            } else if (st.isEof && nativeEndTriggeredForId !== curId) {
              nativeEndTriggeredForId = curId;
              if (
                cfMs === 0 &&
                state.settings.gaplessEnabled !== false &&
                nativePreloadSuccessForId === curId &&
                nativePreloadTrackRef &&
                state.sleepMode !== "end_of_track"
              ) {
                const promoted = await api.nativePromotePreloaded();
                if (promoted) {
                  const { track: nTrack, idx: nIdx } = nativePreloadTrackRef;
                  state.currentIndex = nIdx;
                  applyNowPlayingMetadata(nTrack);
                  return;
                }
              }
              await playNextTrack(true);
            }
          }
        } else {
          const audioEl = getActiveAudio();
          const curMs = (audioEl.currentTime || 0) * 1000;
          const durMs =
            Number.isFinite(audioEl.duration) && audioEl.duration > 0
              ? audioEl.duration * 1000
              : state.currentTrack?.durationMs || 0;
          const rem = durMs - curMs;
          nearTransition = durMs > 0 && rem <= cfMs + 4500;

          if (shouldRenderUi) {
            renderProgressFrame(curMs, durMs);
            lastProgressRenderAtMs = Date.now();
          }

          if (state.settings.gaplessEnabled !== false && cfMs === 0 && state.repeatMode !== "one" && rem <= 3500 && rem > 250) {
            const curId = state.currentTrack?.id;
            if (webaudioGaplessPreloadedForId !== curId) {
              const nextIdx = getNextQueueIndex();
              const nextTrack = nextIdx >= 0 ? state.queue[nextIdx] : null;
              if (nextTrack && !isNativeTrackSupported(nextTrack)) {
                webaudioGaplessPreloadedForId = curId;
                const inactive = decks[1 - activeDeckIdx].el;
                inactive.src = toFileUrl(nextTrack.path);
                inactive.load();
              }
            }
          }

          if (cfMs > 0 && state.repeatMode !== "one" && durMs > cfMs * 2.2 && !audioEl.paused) {
            if (rem <= cfMs && rem > 180 && state.crossfadeTriggeredForId !== state.currentTrack?.id) {
              const nextIdx = getNextQueueIndex();
              if (nextIdx >= 0) {
                state.crossfadeTriggeredForId = state.currentTrack?.id;
                startCrossfadeToTrack(nextIdx, Math.min(cfMs, rem));
              }
            }
          }
        }

        if (nearTransition) {
          nextDelayMs = 60;
        } else if (document.hidden) {
          nextDelayMs = 550;
        } else if (ps.limitUiRefresh) {
          nextDelayMs = ps.mode === "balanced" ? 220 : 380;
        } else {
          nextDelayMs = 70;
        }
      } finally {
        tickBusy = false;
        setTimeout(runPlaybackTick, nextDelayMs);
      }
      return;
    }
    setTimeout(runPlaybackTick, 450);
  };
  setTimeout(runPlaybackTick, 120);

  decks.forEach((deck) => {
    deck.el.addEventListener("ended", () => {
      if (activeBackend !== "webaudio" || deck.idx !== activeDeckIdx) return;
      playNextTrack(true);
    });
    deck.el.addEventListener("error", () => {
      if (activeBackend !== "webaudio" || deck.idx !== activeDeckIdx || !state.currentTrack) return;
      handlePlaybackOpenFailure(state.currentTrack);
    });
  });

  const onVolChange = (e) => {
    const pct = parseInt(e.target.value, 10);
    volBar.value = pct;
    fsVolBar.value = pct;
    updateSliderFill(volBar);
    updateSliderFill(fsVolBar);
    state.settings.volume = pct / 100;
    document.getElementById("vol-num").textContent = `${pct}%`;
    applyVolumeToAudioGraph();
    scheduleSaveSettings();
  };
  volBar.oninput = onVolChange;
  fsVolBar.oninput = onVolChange;

  // New playlist modal
  const plModal = document.getElementById("playlist-modal");
  const plInput = document.getElementById("playlist-name-input");
  document.getElementById("btn-new-playlist").onclick = () => {
    state.pendingTrackForNewPlaylist = null;
    plInput.value = "";
    plModal.classList.remove("hidden");
    plInput.focus();
  };
  document.getElementById("btn-cancel-pl").onclick = () => {
    state.pendingTrackForNewPlaylist = null;
    plModal.classList.add("hidden");
  };
  document.getElementById("btn-confirm-pl").onclick = async () => {
    const name = plInput.value.trim();
    if (name) {
      state.playlists = await api.createPlaylist(name);
      if (state.pendingTrackForNewPlaylist && state.playlists.length > 0) {
        const newest = state.playlists[state.playlists.length - 1];
        state.playlists = await api.addTrackToPlaylist(newest.id, state.pendingTrackForNewPlaylist);
      }
      renderSidebarPlaylists();
    }
    state.pendingTrackForNewPlaylist = null;
    plModal.classList.add("hidden");
  };

  window.addEventListener("resize", () => {
    updateSidebarCompactState();
    if (state.currentView === "equalizer") drawEqResponseCurve();
  });

  window.addEventListener("keydown", (e) => {
    if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT") return;
    if (e.code === "Space") {
      e.preventDefault();
      togglePlayPause();
    } else if (e.code === "Escape") {
      closeFullscreen();
      plModal.classList.add("hidden");
      sleepModal.classList.add("hidden");
      expModal.classList.add("hidden");
      document.getElementById("track-info-modal").classList.add("hidden");
      document.getElementById("playlist-popover").classList.add("hidden");
      document.getElementById("track-menu-popover").classList.add("hidden");
    } else if (e.code === "ArrowRight") {
      seekToSeconds(getCurrentPlaybackTimeSec() + 5);
    } else if (e.code === "ArrowLeft") {
      seekToSeconds(Math.max(0, getCurrentPlaybackTimeSec() - 5));
    }
  });

  api.onMediaAction((action) => {
    const type = typeof action === "string" ? action : action?.type;
    if (!type) return;
    if (type === "toggle") togglePlayPause();
    else if (type === "play") {
      if (!state.isPlaying) togglePlayPause();
    } else if (type === "pause") {
      if (state.isPlaying) togglePlayPause();
    } else if (type === "stop") {
      stopPlayback();
    } else if (type === "next") playNextTrack();
    else if (type === "prev") playPrevTrack();
    else if (type === "seekRelative" && Number.isFinite(action?.numberValue)) {
      seekToSeconds(Math.max(0, getCurrentPlaybackTimeSec() + action.numberValue));
    } else if (type === "seekAbsolute" && Number.isFinite(action?.numberValue)) {
      seekToSeconds(Math.max(0, action.numberValue));
    } else if (type === "setVolume" && Number.isFinite(action?.numberValue)) {
      const clamped = Math.max(0, Math.min(1, action.numberValue));
      const pct = Math.round(clamped * 100);
      state.settings.volume = clamped;
      volBar.value = pct;
      fsVolBar.value = pct;
      updateSliderFill(volBar);
      updateSliderFill(fsVolBar);
      document.getElementById("vol-num").textContent = `${pct}%`;
      applyVolumeToAudioGraph();
      scheduleSaveSettings();
    } else if (type === "setShuffle" && typeof action?.boolValue === "boolean") {
      if (state.isShuffle !== action.boolValue) {
        toggleShuffle();
      } else {
        pushNativeMprisState(false);
      }
    } else if (type === "setLoopStatus" && typeof action?.stringValue === "string") {
      const mapped =
        action.stringValue === "Track"
          ? "one"
          : action.stringValue === "Playlist"
            ? "all"
            : "off";
      state.repeatMode = mapped;
      updateTransportState();
    }
  });

  window.addEventListener("beforeunload", () => {
    persistPlaybackSnapshot(true);
  });
});

function restoreLastPlaybackState(lastPlayback) {
  if (!lastPlayback || !lastPlayback.trackId || !state.tracks.length) return;
  const tracksById = new Map(state.tracks.map((t) => [t.id, t]));
  const targetTrack = tracksById.get(lastPlayback.trackId);
  if (!targetTrack) return;

  const reconstructed = Array.isArray(lastPlayback.queueIds)
    ? lastPlayback.queueIds.map((id) => tracksById.get(id)).filter(Boolean)
    : [];
  const restoredQueue = reconstructed.length ? reconstructed : getVisibleTracks();
  state.originalQueue = [...restoredQueue];
  state.queue = [...restoredQueue];
  state.currentIndex = Math.max(0, state.queue.findIndex((t) => t.id === targetTrack.id));

  const durSec = (targetTrack.durationMs || 0) / 1000;
  const rawPos = Number(lastPlayback.positionSec) || 0;
  const clampedPos = durSec > 1 ? Math.max(0, Math.min(durSec - 0.5, rawPos)) : Math.max(0, rawPos);

  activeBackend = isNativeTrackSupported(targetTrack) ? "native" : "webaudio";
  pendingResumeTrackId = targetTrack.id;
  pendingResumePositionSec = clampedPos;
  nativeStatusCache.positionSec = clampedPos;
  nativeStatusCache.durationSec = durSec;
  syncPlaybackAnchor(clampedPos);
  state.isPlaying = false;

  applyNowPlayingMetadata(targetTrack, { recordHistory: false });

  const ratio = durSec > 0 ? Math.min(1, clampedPos / durSec) : 0;
  const sliderVal = Math.round(ratio * 1000);
  ["seek-bar", "fs-seek-bar"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) {
      el.value = sliderVal;
      updateSliderFill(el);
    }
  });
  const curStr = formatTime(clampedPos * 1000);
  const durStr = formatTime(durSec * 1000);
  document.getElementById("time-cur").textContent = curStr;
  document.getElementById("fs-time-cur").textContent = curStr;
  document.getElementById("time-dur").textContent = durStr;
  document.getElementById("fs-time-dur").textContent = durStr;
  updateMprisPositionState();
}
