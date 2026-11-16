const api = window.flacApi;

const EQ_BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
const EQ_LABELS = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"];
const EQ_PRESETS = {
  "Flat": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  "Reference": [1.5, 1.0, 0.0, -0.5, 0.0, 0.5, 1.2, 2.0, 2.5, 3.0],
  "Sub & Bass": [6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.5, 1.0, 1.5, 1.5],
  "Rock": [4.5, 3.5, 1.5, -1.0, -1.5, 1.0, 3.0, 4.0, 4.5, 4.5],
  "Electronic": [5.5, 4.5, 1.5, 0.0, -1.5, 1.5, 1.0, 2.5, 4.5, 5.0],
  "Vocal": [-1.5, -1.0, 0.0, 2.0, 4.0, 4.5, 3.5, 1.5, 0.0, -0.5],
  "Jazz": [3.0, 2.5, 1.0, 1.5, -1.0, -1.0, 0.0, 1.5, 3.0, 3.5],
  "Classical": [3.5, 2.5, 1.5, 0.0, 0.0, 0.0, -1.0, 1.5, 3.0, 4.0],
};

/* BIT-TRANSPARENT AUDIO ENGINE WITH ACTIVE-ONLY BIQUAD ROUTING */
const audioEl = new Audio();
audioEl.preload = "auto";
audioEl.crossOrigin = "anonymous";

let audioCtx = null;
let sourceNode = null;
let preampNode = null;
let bassNode = null;
let bandNodes = [];
let envelopeNode = null;
let analyserNode = null;
let lastRoutingSignature = "";

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
  sourceNode = audioCtx.createMediaElementSource(audioEl);

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
  analyserNode = audioCtx.createAnalyser();
  analyserNode.fftSize = 128;
  analyserNode.smoothingTimeConstant = 0.8;

  envelopeNode.connect(analyserNode);
  analyserNode.connect(audioCtx.destination);

  applyEqToAudioGraph(true);
  applyVolumeToAudioGraph();
}

function applyEqToAudioGraph(forceRebuild = false) {
  if (!audioCtx) return;
  const cfg = state.eqConfig;
  const active = isEqEffectivelyActive();

  const activeMask = active
    ? `1:${Math.abs(cfg.preampDb) > 0.05 ? 1 : 0}:${cfg.bassBoost > 0.01 ? 1 : 0}:` +
      cfg.bandGains.map((g) => (Math.abs(g) > 0.05 ? 1 : 0)).join("")
    : "0:direct";

  if (forceRebuild || activeMask !== lastRoutingSignature) {
    lastRoutingSignature = activeMask;
    try {
      sourceNode.disconnect();
      preampNode.disconnect();
      bassNode.disconnect();
      bandNodes.forEach((b) => b.disconnect());
    } catch (_) {}

    if (!active) {
      sourceNode.connect(envelopeNode);
    } else {
      let current = sourceNode;
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

function applyVolumeToAudioGraph(crossfadeMultiplier = 1.0) {
  const userVol = Math.max(0, Math.min(1, state.settings.volume ?? 0.85));
  audioEl.volume = userVol;
  if (audioCtx && envelopeNode) {
    const rgBoost = state.settings.replayGainEnabled ? 0.92 : 1.0;
    envelopeNode.gain.setTargetAtTime(
      Math.max(0.0001, crossfadeMultiplier * rgBoost),
      audioCtx.currentTime,
      0.02
    );
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
    closeToTray: true,
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
    appBgCustomPath: "",
    appBgIsVideo: false,
    appBgBlurPx: 48,
    appBgDimPercent: 68,
    fsBgMode: "cover",
    fsBgColorMode: "mono",
    fsBgCustomPath: "",
    fsBgIsVideo: false,
    fsBgBlurPx: 52,
    fsBgDimPercent: 64,
    volume: 0.85,
    outputDeviceId: "default",
    rightDockWidth: 350,
    rightDockOpen: true,
  },
  currentView: "home",
  navHistory: [],
  selectedArtistName: "",
  drilldownTitle: "",
  drilldownTracks: [],
  activePlaylistId: null,
  searchQuery: "",
  sortMode: "artist",
  queue: [],
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
  if (t.format === "FLAC" || t.format === "WAV") {
    return `${t.format} • ${t.bitDepth || 16}-BIT / ${sr} KHZ`;
  }
  return `${t.format} • ${t.bitrateKbps || 320} KBPS / ${sr} KHZ`;
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

function applyAppearanceSettings() {
  const s = state.settings;
  document.body.classList.remove(
    "font-jakarta",
    "font-manrope",
    "font-inter",
    "font-mono",
    "font-system"
  );
  document.body.classList.add(`font-${s.fontFamily || "jakarta"}`);

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

  const liftC = (v, amt) => Math.max(0, Math.min(255, v + amt));
  const pOp = Math.max(0.2, Math.min(1, (s.panelOpacity ?? 88) / 100));
  const cOp = Math.max(0.2, Math.min(1, (s.cardOpacity ?? 86) / 100));

  root.setProperty("--bg-1", `rgba(${pr}, ${pg}, ${pb}, ${pOp.toFixed(2)})`);
  root.setProperty("--card-bg", `rgba(${liftC(pr, 4)}, ${liftC(pg, 4)}, ${liftC(pb, 4)}, ${cOp.toFixed(2)})`);
  root.setProperty(
    "--bg-2",
    `rgba(${liftC(pr, 18)}, ${liftC(pg, 18)}, ${liftC(pb, 18)}, ${Math.min(1, pOp + 0.05).toFixed(2)})`
  );
  root.setProperty("--bg-3", `rgba(${liftC(pr, 30)}, ${liftC(pg, 30)}, ${liftC(pb, 30)}, 0.92)`);
  root.setProperty("--bg-hover", `rgba(${liftC(pr, 38)}, ${liftC(pg, 38)}, ${liftC(pb, 38)}, 0.95)`);

  const panelBlur = s.panelBlurPx ?? 14;
  root.setProperty("--panel-blur", panelBlur > 0 ? `blur(${panelBlur}px)` : "none");

  const rad = s.borderRadiusPx ?? 12;
  root.setProperty("--r-sm", `${Math.max(2, Math.round(rad * 0.68))}px`);
  root.setProperty("--r-md", `${rad}px`);
  root.setProperty("--r-lg", `${Math.max(4, Math.round(rad * 1.25))}px`);

  const bOp = Math.max(0, Math.min(0.45, (s.borderOpacity ?? 10) / 100));
  root.setProperty("--line", `rgba(255, 255, 255, ${bOp.toFixed(2)})`);
  root.setProperty("--line-strong", `rgba(255, 255, 255, ${Math.min(0.6, bOp * 2.2).toFixed(2)})`);

  const appBlur = s.appBgBlurPx ?? s.bgBlurPx ?? 48;
  const appDim = s.appBgDimPercent ?? s.bgDimPercent ?? 68;
  const appGray = (s.appBgColorMode || "mono") === "color" ? "0%" : "100%";

  const fsBlur = s.fsBgBlurPx ?? s.bgBlurPx ?? 52;
  const fsDim = s.fsBgDimPercent ?? s.bgDimPercent ?? 64;
  const fsGray = (s.fsBgColorMode || "mono") === "color" ? "0%" : "100%";

  root.setProperty("--app-bg-blur", `${appBlur}px`);
  root.setProperty("--app-bg-dim", `${appDim / 100}`);
  root.setProperty("--app-bg-gray", appGray);

  root.setProperty("--fs-bg-blur", `${fsBlur}px`);
  root.setProperty("--fs-bg-dim", `${fsDim / 100}`);
  root.setProperty("--fs-bg-gray", fsGray);

  const specCanvas = document.getElementById("mini-spectrum");
  specCanvas.classList.toggle("hidden", !s.visualizerEnabled);

  updateSidebarCompactState();
  updateBackgroundLayers();
  updateCountsAndBadges();
  updateBarNowPlaying();
  drawEqResponseCurve();
}

function updateSidebarCompactState() {
  const sidebar = document.getElementById("left-sidebar");
  const isNarrow = window.innerWidth < 1100;
  const compact = Boolean(state.settings.sidebarCompact) || isNarrow;
  sidebar.classList.toggle("rail-mode", compact);
}

function updateBackgroundLayers() {
  const s = state.settings;
  const coverUrl = state.currentTrack?.coverPath ? toFileUrl(state.currentTrack.coverPath) : "";
  const appDim = s.appBgDimPercent ?? s.bgDimPercent ?? 68;
  const fsDim = s.fsBgDimPercent ?? s.bgDimPercent ?? 64;

  const applyLayer = (mode, customPath, isVideo, dimPct, imgId, vidId, scrimId) => {
    const imgEl = document.getElementById(imgId);
    const vidEl = document.getElementById(vidId);
    const scrimEl = document.getElementById(scrimId);

    if (mode === "none") {
      imgEl.classList.add("hidden");
      vidEl.classList.add("hidden");
      vidEl.pause();
      scrimEl.style.background = "#000000";
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
        vidEl.play().catch(() => {});
      } else {
        vidEl.classList.add("hidden");
        vidEl.pause();
        imgEl.src = fileUrl;
        imgEl.classList.remove("hidden");
      }
    } else {
      imgEl.classList.add("hidden");
      vidEl.classList.add("hidden");
    }
  };

  applyLayer(
    s.appBgMode || "none",
    s.appBgCustomPath,
    s.appBgIsVideo,
    appDim,
    "app-bg-img",
    "app-bg-video",
    "app-bg-scrim"
  );
  applyLayer(
    s.fsBgMode || "cover",
    s.fsBgCustomPath,
    s.fsBgIsVideo,
    fsDim,
    "fs-bg-img",
    "fs-bg-video",
    "fs-bg-scrim"
  );
}

function updateCountsAndBadges() {
  document.getElementById("nav-count-tracks").textContent = state.tracks.length;
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

  if (state.currentView === "home") {
    renderHome(view);
  } else if (state.currentView === "tracks") {
    renderTrackTable(view, "Треки", state.tracks);
  } else if (state.currentView === "history") {
    const historyTracks = (state.recentlyPlayedIds || [])
      .map((id) => state.tracks.find((t) => t.id === id))
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
    const recentAddedTracks = [...state.tracks].sort(
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
      state.tracks.filter((t) => favSet.has(t.id))
    );
  } else if (state.currentView === "playlist") {
    const pl = state.playlists.find((p) => p.id === state.activePlaylistId);
    if (!pl) return navigateToView("tracks", false);
    const idSet = new Set(pl.trackIds);
    renderTrackTable(
      view,
      pl.name,
      state.tracks.filter((t) => idSet.has(t.id)),
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
  if (state.tracks.length === 0) {
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
  state.tracks.forEach((t) => {
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
        <div class="mix-chip-sub">${state.tracks.length} песен</div>
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
    state.isShuffle = true;
    updateTransportState();
    const shuffled = [...state.tracks].sort(() => Math.random() - 0.5);
    if (shuffled.length) playTrackWithQueue(shuffled[0], shuffled);
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
    .map((id) => state.tracks.find((t) => t.id === id))
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
            createHorizontalCard(tr.title, tr.artist, tr.thumbPath || tr.coverPath, () =>
              playTrackWithQueue(tr, allRecentPlayed)
            )
          );
        });
      }
    );
  }

  // 3. Недавно добавленные
  const allRecentlyAdded = [...state.tracks].sort(
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
          createHorizontalCard(tr.title, tr.artist, tr.thumbPath || tr.coverPath, () =>
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
        const sample = list.find((t) => t.thumbPath || t.coverPath) || list[0];
        row.appendChild(
          createHorizontalCard(
            artistName,
            `${list.length} треков`,
            sample?.thumbPath || sample?.coverPath,
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
        const sample = list.find((t) => t.thumbPath || t.coverPath) || list[0];
        row.appendChild(
          createHorizontalCard(
            albumName,
            sample?.artist || "",
            sample?.thumbPath || sample?.coverPath,
            () => openAlbumDrilldown(albumName, list)
          )
        );
      });
    }
  );
}

function createHorizontalCard(title, subtitle, coverPath, onClick) {
  const card = document.createElement("div");
  card.className = "h-card";
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
  const sample = tracks.find((t) => t.thumbPath || t.coverPath) || tracks[0];
  const imgSrc = sample?.thumbPath || sample?.coverPath;
  const card = document.createElement("div");
  card.className = "grid-card";
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
  getFilteredTracks(state.tracks).forEach((t) => {
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
  getFilteredTracks(state.tracks).forEach((t) => {
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
  const artistTracks = state.tracks.filter(
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
    state.isShuffle = true;
    updateTransportState();
    const shuffled = [...artistTracks].sort(() => Math.random() - 0.5);
    if (shuffled.length) playTrackWithQueue(shuffled[0], shuffled);
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
  header.querySelector("#btn-shuffle-list").onclick = () => {
    if (!filtered.length) return;
    state.isShuffle = true;
    updateTransportState();
    const shuffled = [...filtered].sort(() => Math.random() - 0.5);
    playTrackWithQueue(shuffled[0], shuffled);
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
    const thumb = t.thumbPath || t.coverPath;
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
      <button class="icon-btn" data-act="pl" title="Добавить в плейлист">+</button>
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

  ctx.strokeStyle = "#1c1c1c";
  ctx.lineWidth = 1;
  [0.2, 0.5, 0.8].forEach((ry) => {
    ctx.beginPath();
    ctx.moveTo(0, h * ry);
    ctx.lineTo(w, h * ry);
    ctx.stroke();
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

  const persistEq = () => {
    applyEqToAudioGraph();
    updateCountsAndBadges();
    drawEqResponseCurve();
    api.saveEqConfig(state.eqConfig);
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
    persistEq();
  };
  slBass.oninput = (e) => {
    const v = parseFloat(e.target.value);
    updateSliderFill(slBass);
    state.eqConfig.enabled = true;
    state.eqConfig.bassBoost = v;
    document.getElementById("val-bass").textContent = `+${(v * 12).toFixed(1)} dB`;
    persistEq();
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
      persistEq();
    };
  });
}

async function renderSettings(container) {
  const s = state.settings;
  let audioDevices = [];
  try {
    const all = await navigator.mediaDevices.enumerateDevices();
    audioDevices = all.filter((d) => d.kind === "audiooutput");
  } catch (_) {}

  container.innerHTML = `
    <div class="view-header">
      <div>
        <div class="view-title">Настройки</div>
      </div>
      <div class="view-actions">
        <button id="btn-theme-import" class="mono-btn">Импорт темы</button>
        <button id="btn-theme-export" class="mono-btn solid">Поделиться темой</button>
      </div>
    </div>

    <div class="cards-stack">
      <!-- 1. Theme Studio & Presets -->
      <div class="mono-card">
        <div class="nav-label" style="padding:0 0 10px;">ТЕМА И ОФОРМЛЕНИЕ</div>
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

        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Яркость цвета меню: <span id="lbl-panel-bright">${s.panelBrightness ?? 100}%</span></div>
          </div>
          <input id="set-panel-bright" type="range" min="40" max="220" step="5" value="${s.panelBrightness ?? 100}" class="mobile-slider" style="max-width:130px;" />
        </div>

        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Прозрачность меню: <span id="lbl-panel-op">${s.panelOpacity ?? 88}%</span></div>
          </div>
          <input id="set-panel-op" type="range" min="20" max="100" step="2" value="${s.panelOpacity ?? 88}" class="mobile-slider" style="max-width:130px;" />
        </div>

        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Прозрачность карточек: <span id="lbl-card-op">${s.cardOpacity ?? 86}%</span></div>
          </div>
          <input id="set-card-op" type="range" min="20" max="100" step="2" value="${s.cardOpacity ?? 86}" class="mobile-slider" style="max-width:130px;" />
        </div>

        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Блюр панелей: <span id="lbl-panel-blur">${s.panelBlurPx ?? 14} px</span></div>
          </div>
          <input id="set-panel-blur" type="range" min="0" max="40" step="2" value="${s.panelBlurPx ?? 14}" class="mobile-slider" style="max-width:130px;" />
        </div>

        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Скругление углов: <span id="lbl-radius">${s.borderRadiusPx ?? 12} px</span></div>
          </div>
          <input id="set-radius" type="range" min="2" max="22" step="1" value="${s.borderRadiusPx ?? 12}" class="mobile-slider" style="max-width:130px;" />
        </div>

        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Яркость рамок: <span id="lbl-border-op">${s.borderOpacity ?? 10}%</span></div>
          </div>
          <input id="set-border-op" type="range" min="0" max="35" step="1" value="${s.borderOpacity ?? 10}" class="mobile-slider" style="max-width:130px;" />
        </div>

        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Блюр неактивных строк текста: <span id="lbl-lyrics-blur">${s.lyricsBlurIntensity ?? 100}%</span></div>
          </div>
          <input id="set-lyrics-blur" type="range" min="0" max="180" step="10" value="${s.lyricsBlurIntensity ?? 100}" class="mobile-slider" style="max-width:130px;" />
        </div>
      </div>

      <!-- 2. Fonts & Interface -->
      <div class="mono-card">
        <div class="nav-label" style="padding:0 0 10px;">ШРИФТ И ИНТЕРФЕЙС</div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Шрифт приложения</div>
          </div>
          <select id="set-font" class="mono-select">
            <option value="jakarta" ${s.fontFamily === "jakarta" ? "selected" : ""}>Google Sans / Jakarta</option>
            <option value="manrope" ${s.fontFamily === "manrope" ? "selected" : ""}>Manrope</option>
            <option value="inter" ${s.fontFamily === "inter" ? "selected" : ""}>Inter</option>
            <option value="mono" ${s.fontFamily === "mono" ? "selected" : ""}>JetBrains Mono</option>
            <option value="system" ${s.fontFamily === "system" ? "selected" : ""}>Системный</option>
          </select>
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Значки качества треков</div>
            <div class="view-subtitle">В списке и мини-плеере</div>
          </div>
          <input id="set-quality-badges" type="checkbox" ${s.showQualityBadges ? "checked" : ""} />
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Компактный список треков</div>
          </div>
          <input id="set-compact" type="checkbox" ${s.compactMode ? "checked" : ""} />
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Номера треков</div>
          </div>
          <input id="set-nums" type="checkbox" ${s.showTrackNumbers ? "checked" : ""} />
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Сворачивать в трей при закрытии</div>
          </div>
          <input id="set-tray" type="checkbox" ${s.closeToTray ? "checked" : ""} />
        </div>

        <div class="nav-label" style="padding:14px 0 8px;">ПАПКИ С МУЗЫКОЙ</div>
        ${
          state.folders.length === 0
            ? `<div class="view-subtitle" style="margin-bottom:10px;">Папки не добавлены</div>`
            : state.folders
                .map(
                  (f) => `
              <div class="setting-line">
                <span style="font-size:12px;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(f)}</span>
                <button class="mono-btn tiny btn-rm-folder" data-folder="${escapeHtml(f)}">Удалить</button>
              </div>
            `
                )
                .join("")
        }
        <div style="display:flex;gap:8px;margin-top:12px;">
          <button id="btn-set-add-folder" class="mono-btn solid">Добавить папку</button>
          <button id="btn-set-rescan" class="mono-btn">Пересканировать</button>
        </div>
      </div>

      <!-- 3. Main Window Background -->
      <div class="mono-card">
        <div class="nav-label" style="padding:0 0 10px;">ФОН ГЛАВНОГО ОКНА</div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Режим фона</div>
          </div>
          <select id="set-app-bg-mode" class="mono-select">
            <option value="none" ${s.appBgMode === "none" ? "selected" : ""}>Чистый чёрный</option>
            <option value="cover" ${s.appBgMode === "cover" ? "selected" : ""}>Размытая обложка</option>
            <option value="custom" ${s.appBgMode === "custom" ? "selected" : ""}>Свой файл</option>
          </select>
        </div>
        <div class="setting-line">
          <div style="font-size:11.5px;color:var(--fg-2);overflow:hidden;text-overflow:ellipsis;">
            ${s.appBgCustomPath ? `Файл: ${escapeHtml(s.appBgCustomPath.split(/[\\/]/).pop())}` : "Файл не выбран"}
          </div>
          <button id="btn-pick-app-bg" class="mono-btn tiny">Выбрать файл...</button>
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Цветовой режим</div>
          </div>
          <select id="set-app-bg-color" class="mono-select">
            <option value="mono" ${(s.appBgColorMode || "mono") === "mono" ? "selected" : ""}>Монохром</option>
            <option value="color" ${s.appBgColorMode === "color" ? "selected" : ""}>Цветной</option>
          </select>
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Размытие: <span id="lbl-app-bg-blur">${s.appBgBlurPx ?? 48} px</span></div>
          </div>
          <input id="set-app-bg-blur" type="range" min="0" max="90" step="2" value="${s.appBgBlurPx ?? 48}" class="mobile-slider" style="max-width:130px;" />
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Затемнение: <span id="lbl-app-bg-dim">${s.appBgDimPercent ?? 68}%</span></div>
          </div>
          <input id="set-app-bg-dim" type="range" min="15" max="95" step="5" value="${s.appBgDimPercent ?? 68}" class="mobile-slider" style="max-width:130px;" />
        </div>
      </div>

      <!-- 4. Fullscreen Player Background -->
      <div class="mono-card">
        <div class="nav-label" style="padding:0 0 10px;">ФОН ПОЛНОЭКРАННОГО ПЛЕЕРА</div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Режим фона</div>
          </div>
          <select id="set-fs-bg-mode" class="mono-select">
            <option value="none" ${s.fsBgMode === "none" ? "selected" : ""}>Чистый чёрный</option>
            <option value="cover" ${s.fsBgMode === "cover" ? "selected" : ""}>Размытая обложка</option>
            <option value="custom" ${s.fsBgMode === "custom" ? "selected" : ""}>Свой файл</option>
          </select>
        </div>
        <div class="setting-line">
          <div style="font-size:11.5px;color:var(--fg-2);overflow:hidden;text-overflow:ellipsis;">
            ${s.fsBgCustomPath ? `Файл: ${escapeHtml(s.fsBgCustomPath.split(/[\\/]/).pop())}` : "Файл не выбран"}
          </div>
          <button id="btn-pick-fs-bg" class="mono-btn tiny">Выбрать файл...</button>
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Цветовой режим</div>
          </div>
          <select id="set-fs-bg-color" class="mono-select">
            <option value="mono" ${(s.fsBgColorMode || "mono") === "mono" ? "selected" : ""}>Монохром</option>
            <option value="color" ${s.fsBgColorMode === "color" ? "selected" : ""}>Цветной</option>
          </select>
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Размытие: <span id="lbl-fs-bg-blur">${s.fsBgBlurPx ?? 52} px</span></div>
          </div>
          <input id="set-fs-bg-blur" type="range" min="0" max="90" step="2" value="${s.fsBgBlurPx ?? 52}" class="mobile-slider" style="max-width:130px;" />
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Затемнение: <span id="lbl-fs-bg-dim">${s.fsBgDimPercent ?? 64}%</span></div>
          </div>
          <input id="set-fs-bg-dim" type="range" min="15" max="95" step="5" value="${s.fsBgDimPercent ?? 64}" class="mobile-slider" style="max-width:130px;" />
        </div>
      </div>

      <!-- 5. Visualizer, Audio Output (DAC) & Playback -->
      <div class="mono-card">
        <div class="nav-label" style="padding:0 0 10px;">ЗВУК И ВИЗУАЛИЗАТОР</div>
        <div class="setting-line" style="flex-direction:column;align-items:stretch;gap:8px;">
          <div style="font-size:13px;font-weight:700;">Устройство вывода звука</div>
          <select id="set-dac-select" class="mono-select" style="width:100%;">
            <option value="default">Системный выход по умолчанию</option>
            ${audioDevices
              .map(
                (d) =>
                  `<option value="${escapeHtml(d.deviceId)}" ${
                    s.outputDeviceId === d.deviceId ? "selected" : ""
                  }>${escapeHtml(d.label || `Устройство (${d.deviceId.slice(0, 8)})`)}</option>`
              )
              .join("")}
          </select>
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Визуализатор спектра</div>
          </div>
          <input id="set-vis-on" type="checkbox" ${s.visualizerEnabled ? "checked" : ""} />
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Стиль визуализатора</div>
          </div>
          <select id="set-vis-style" class="mono-select">
            <option value="bars" ${s.visualizerStyle === "bars" ? "selected" : ""}>Столбики</option>
            <option value="wave" ${s.visualizerStyle === "wave" ? "selected" : ""}>Волна</option>
            <option value="mirror" ${s.visualizerStyle === "mirror" ? "selected" : ""}>Импульс</option>
          </select>
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Кроссфейд: <span id="lbl-cf">${s.crossfadeSeconds} сек</span></div>
          </div>
          <input id="set-cf" type="range" min="0" max="10" step="1" value="${s.crossfadeSeconds}" class="mobile-slider" style="max-width:120px;" />
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">ReplayGain</div>
          </div>
          <input id="set-rg" type="checkbox" ${s.replayGainEnabled ? "checked" : ""} />
        </div>
        <div class="setting-line">
          <div>
            <div style="font-size:13px;font-weight:700;">Gapless</div>
          </div>
          <input id="set-gapless" type="checkbox" ${s.gaplessEnabled ? "checked" : ""} />
        </div>
      </div>
    </div>
  `;

  [
    "set-panel-bright",
    "set-panel-op",
    "set-card-op",
    "set-panel-blur",
    "set-radius",
    "set-border-op",
    "set-lyrics-blur",
    "set-app-bg-blur",
    "set-app-bg-dim",
    "set-fs-bg-blur",
    "set-fs-bg-dim",
    "set-cf",
  ].forEach((id) => updateSliderFill(document.getElementById(id)));

  const save = () => {
    applyAppearanceSettings();
    applyVolumeToAudioGraph();
    api.saveSettings(state.settings);
  };

  document.getElementById("btn-theme-export").onclick = async () => {
    await api.exportTheme("FlacTheme");
  };
  document.getElementById("btn-theme-import").onclick = async () => {
    const res = await api.importTheme();
    if (res && !res.canceled && !res.error && res.settings) {
      state.settings = { ...state.settings, ...res.settings };
      applyAppearanceSettings();
      renderSettings(container);
    }
  };

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

  document.getElementById("set-accent-color").oninput = (e) => {
    state.settings.accentColor = e.target.value;
    document.getElementById("lbl-accent-hex").textContent = e.target.value;
    save();
  };
  document.getElementById("set-panel-color").oninput = (e) => {
    state.settings.panelColor = e.target.value;
    document.getElementById("lbl-panel-hex").textContent = e.target.value;
    save();
  };

  const bindRangeSetting = (inputId, labelId, prop, suffix) => {
    const el = document.getElementById(inputId);
    el.oninput = (e) => {
      updateSliderFill(el);
      state.settings[prop] = parseInt(e.target.value, 10);
      document.getElementById(labelId).textContent = `${state.settings[prop]}${suffix}`;
      if (prop === "lyricsBlurIntensity") updateActiveLyricLine(true);
      save();
    };
  };

  bindRangeSetting("set-panel-bright", "lbl-panel-bright", "panelBrightness", "%");
  bindRangeSetting("set-panel-op", "lbl-panel-op", "panelOpacity", "%");
  bindRangeSetting("set-card-op", "lbl-card-op", "cardOpacity", "%");
  bindRangeSetting("set-panel-blur", "lbl-panel-blur", "panelBlurPx", " px");
  bindRangeSetting("set-radius", "lbl-radius", "borderRadiusPx", " px");
  bindRangeSetting("set-border-op", "lbl-border-op", "borderOpacity", "%");
  bindRangeSetting("set-lyrics-blur", "lbl-lyrics-blur", "lyricsBlurIntensity", "%");
  bindRangeSetting("set-app-bg-blur", "lbl-app-bg-blur", "appBgBlurPx", " px");
  bindRangeSetting("set-app-bg-dim", "lbl-app-bg-dim", "appBgDimPercent", "%");
  bindRangeSetting("set-fs-bg-blur", "lbl-fs-bg-blur", "fsBgBlurPx", " px");
  bindRangeSetting("set-fs-bg-dim", "lbl-fs-bg-dim", "fsBgDimPercent", "%");
  bindRangeSetting("set-cf", "lbl-cf", "crossfadeSeconds", " сек");

  document.getElementById("set-dac-select").onchange = async (e) => {
    const id = e.target.value;
    state.settings.outputDeviceId = id;
    if (typeof audioEl.setSinkId === "function") {
      try {
        await audioEl.setSinkId(id === "default" ? "" : id);
      } catch (_) {}
    }
    api.saveSettings(state.settings);
  };

  document.getElementById("btn-set-add-folder").onclick = handlePickFolder;
  document.getElementById("btn-set-rescan").onclick = async () => {
    const res = await api.rescanFolders();
    state.tracks = res.tracks;
    updateCountsAndBadges();
    renderSettings(container);
  };
  container.querySelectorAll(".btn-rm-folder").forEach((b) => {
    b.onclick = async () => {
      const res = await api.removeFolder(b.dataset.folder);
      state.folders = res.folders;
      state.tracks = res.tracks;
      updateCountsAndBadges();
      renderSettings(container);
    };
  });

  document.getElementById("set-font").onchange = (e) => {
    state.settings.fontFamily = e.target.value;
    save();
  };
  document.getElementById("set-quality-badges").onchange = (e) => {
    state.settings.showQualityBadges = e.target.checked;
    save();
  };
  document.getElementById("set-compact").onchange = (e) => {
    state.settings.compactMode = e.target.checked;
    save();
  };
  document.getElementById("set-nums").onchange = (e) => {
    state.settings.showTrackNumbers = e.target.checked;
    save();
  };
  document.getElementById("set-tray").onchange = (e) => {
    state.settings.closeToTray = e.target.checked;
    save();
  };

  document.getElementById("set-app-bg-mode").onchange = (e) => {
    state.settings.appBgMode = e.target.value;
    save();
  };
  document.getElementById("set-app-bg-color").onchange = (e) => {
    state.settings.appBgColorMode = e.target.value;
    save();
  };
  document.getElementById("btn-pick-app-bg").onclick = async () => {
    const picked = await api.pickBackgroundFile();
    if (picked) {
      state.settings.appBgCustomPath = picked.path;
      state.settings.appBgIsVideo = picked.isVideo;
      state.settings.appBgMode = "custom";
      save();
      renderSettings(container);
    }
  };

  document.getElementById("set-fs-bg-mode").onchange = (e) => {
    state.settings.fsBgMode = e.target.value;
    save();
  };
  document.getElementById("set-fs-bg-color").onchange = (e) => {
    state.settings.fsBgColorMode = e.target.value;
    save();
  };
  document.getElementById("btn-pick-fs-bg").onclick = async () => {
    const picked = await api.pickBackgroundFile();
    if (picked) {
      state.settings.fsBgCustomPath = picked.path;
      state.settings.fsBgIsVideo = picked.isVideo;
      state.settings.fsBgMode = "custom";
      save();
      renderSettings(container);
    }
  };

  document.getElementById("set-vis-on").onchange = (e) => {
    state.settings.visualizerEnabled = e.target.checked;
    save();
  };
  document.getElementById("set-vis-style").onchange = (e) => {
    state.settings.visualizerStyle = e.target.value;
    save();
  };

  document.getElementById("set-rg").onchange = (e) => {
    state.settings.replayGainEnabled = e.target.checked;
    save();
  };
  document.getElementById("set-gapless").onchange = (e) => {
    state.settings.gaplessEnabled = e.target.checked;
    save();
  };
}

/* LINUX / NOCTALIA MPRIS MEDIA SESSION METADATA & CONTROLS */
async function syncMprisMediaSession(track) {
  if (!("mediaSession" in navigator) || !track) return;
  try {
    let artworkList = [];
    if (track.coverPath) {
      const dataUrl = await api.getCoverDataUrl(track.coverPath);
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

function updateMprisPositionState() {
  if (!("mediaSession" in navigator) || !navigator.mediaSession.setPositionState) return;
  const dur = audioEl.duration;
  const pos = audioEl.currentTime;
  if (Number.isFinite(dur) && dur > 0 && Number.isFinite(pos) && pos >= 0 && pos <= dur) {
    try {
      navigator.mediaSession.setPositionState({
        duration: dur,
        playbackRate: audioEl.playbackRate || 1.0,
        position: pos,
      });
    } catch (_) {}
  }
}

/* PLAYBACK CONTROL */
async function playTrackWithQueue(track, queueList) {
  if (queueList && queueList.length) {
    state.queue = [...queueList];
  }
  state.currentIndex = state.queue.findIndex((t) => t.id === track.id);
  await startTrack(track);
}

async function startTrack(track) {
  if (!track) return;
  ensureAudioGraph();

  state.currentTrack = track;
  state.crossfadeTriggeredForId = null;
  audioEl.src = toFileUrl(track.path);
  try {
    await audioEl.play();
    state.isPlaying = true;
  } catch (err) {
    console.error("Audio play error:", err);
  }

  api.recordTrackPlayed(track.id).then((ids) => {
    state.recentlyPlayedIds = ids || state.recentlyPlayedIds;
  });

  document.title = `${track.title} — ${track.artist} • FlacPlayer`;
  updateBarNowPlaying();
  updateBackgroundLayers();
  syncMprisMediaSession(track);
  renderQueueDock();
  highlightActiveRowInDOM();
  fetchAndRenderLyrics(track);
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

  if (!t) return;
  const badge = formatTrackBadge(t);
  const showBadges = Boolean(state.settings.showQualityBadges);

  // Bottom bar
  document.getElementById("bar-title").textContent = t.title;
  document.getElementById("bar-artist").textContent = `${t.artist} • ${t.album}`;
  const barTag = document.getElementById("bar-format-tag");
  barTag.textContent = badge;
  barTag.classList.toggle("hidden", !showBadges);

  // Right Dock Now Playing Card
  document.getElementById("dock-np-title").textContent = t.title;
  document.getElementById("dock-np-artist").textContent = `${t.artist} • ${t.album}`;
  const dockSpecs = document.getElementById("dock-np-specs");
  dockSpecs.textContent = badge;
  dockSpecs.classList.toggle("hidden", !showBadges);

  // Fullscreen Stage
  document.getElementById("fs-title").textContent = t.title;
  document.getElementById("fs-artist").textContent = t.artist;
  document.getElementById("fs-album").textContent = t.album;

  const isFav = state.favoriteIds.includes(t.id);
  document.getElementById("svg-bar-fav").setAttribute("fill", isFav ? "currentColor" : "none");
  document.getElementById("svg-fs-fav").setAttribute("fill", isFav ? "currentColor" : "none");

  const pairs = [
    ["bar-cover-img", "bar-cover-svg"],
    ["dock-cover-img", "dock-cover-fallback"],
    ["fs-cover-img", "fs-cover-svg"],
  ];

  pairs.forEach(([imgId, fbId]) => {
    const imgEl = document.getElementById(imgId);
    const fbEl = document.getElementById(fbId);
    if (t.coverPath) {
      imgEl.src = toFileUrl(t.coverPath);
      imgEl.classList.remove("hidden");
      fbEl.classList.add("hidden");
    } else {
      imgEl.classList.add("hidden");
      fbEl.classList.remove("hidden");
    }
  });
}

function renderQueueDock() {
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
    `;
    row.onclick = () => {
      state.currentIndex = idx;
      startTrack(t);
    };
    box.appendChild(row);
  });
}

/* SILKY-SMOOTH PHYSICS SCROLL INTERPOLATOR FOR SYNCED LYRICS */
const activeScrollAnimations = new WeakMap();

function smoothScrollContainerTo(container, targetTop, durationMs = 580) {
  if (!container) return;
  const existingId = activeScrollAnimations.get(container);
  if (existingId) cancelAnimationFrame(existingId);

  const startTop = container.scrollTop;
  const maxScroll = Math.max(0, container.scrollHeight - container.clientHeight);
  const clampedTarget = Math.max(0, Math.min(maxScroll, targetTop));
  const delta = clampedTarget - startTop;
  if (Math.abs(delta) < 1) return;

  const startTime = performance.now();
  function step(now) {
    const t = Math.min(1, (now - startTime) / durationMs);
    // Quintic ease-out for buttery smooth deceleration without abrupt steps
    const eased = 1 - Math.pow(1 - t, 4);
    container.scrollTop = startTop + delta * eased;
    if (t < 1) {
      activeScrollAnimations.set(container, requestAnimationFrame(step));
    } else {
      activeScrollAnimations.delete(container);
    }
  }
  activeScrollAnimations.set(container, requestAnimationFrame(step));
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

  tagEl.textContent = `${state.lyrics.isSynced ? "СИНХРОНИЗИРОВАНО" : "ТЕКСТ"} • ${state.lyrics.source}`;

  const populate = (container) => {
    container.innerHTML = "";
    const frag = document.createDocumentFragment();
    state.lyrics.lines.forEach((line) => {
      const div = document.createElement("div");
      div.className = "lyric-row";
      div.textContent = line.text;
      div.onclick = () => {
        if (state.lyrics.isSynced) {
          state.manualLyricsScroll = false;
          const offsetMs = getCurrentTrackLyricOffsetMs();
          audioEl.currentTime = Math.max(0, (line.timestampMs + offsetMs) / 1000);
          updateActiveLyricLine(true);
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
}

function updateActiveLyricLine(force = false) {
  if (!state.lyrics || !state.lyrics.isSynced || !state.lyrics.lines.length) return;
  const posMs = audioEl.currentTime * 1000;
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
    return;
  }
  state.activeLyricIndex = foundIdx;

  const blurScale = (state.settings.lyricsBlurIntensity ?? 100) / 100;

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
        row.style.opacity = "0.7";
        row.style.transform = "scale(1)";
        continue;
      }

      const dist = Math.abs(i - foundIdx);
      let blurPx = 0;
      let alpha = 1.0;
      let scale = 1.03;

      if (dist === 0) {
        blurPx = 0;
        alpha = 1.0;
        scale = 1.03;
      } else if (dist === 1) {
        blurPx = 1.6 * mult;
        alpha = 0.58;
        scale = 0.98;
      } else if (dist === 2) {
        blurPx = 3.4 * mult;
        alpha = 0.38;
        scale = 0.95;
      } else if (dist === 3) {
        blurPx = 5.4 * mult;
        alpha = 0.24;
        scale = 0.93;
      } else {
        blurPx = Math.min(9.5 * mult, (5.4 + (dist - 3) * 1.4) * mult);
        alpha = Math.max(0.1, 0.24 - (dist - 3) * 0.04);
        scale = 0.91;
      }

      row.style.filter = `blur(${blurPx.toFixed(1)}px)`;
      row.style.opacity = alpha.toFixed(2);
      row.style.transform = `scale(${scale})`;
    }

    if (!state.manualLyricsScroll && foundIdx >= 0 && rows[foundIdx]) {
      const activeEl = rows[foundIdx];
      const targetTop =
        activeEl.offsetTop - container.clientHeight / 2 + activeEl.clientHeight / 2;
      smoothScrollContainerTo(container, targetTop, 580);
    }
  });
}

function togglePlayPause() {
  if (!state.currentTrack) {
    if (state.tracks.length > 0) {
      playTrackWithQueue(state.tracks[0], state.tracks);
    }
    return;
  }
  ensureAudioGraph();
  if (audioEl.paused) {
    audioEl.play();
    state.isPlaying = true;
  } else {
    audioEl.pause();
    state.isPlaying = false;
  }
  updateBarNowPlaying();
}

function playNextTrack() {
  if (!state.queue.length) return;
  if (state.repeatMode === "one") {
    audioEl.currentTime = 0;
    audioEl.play();
    return;
  }
  let next = state.currentIndex + 1;
  if (state.isShuffle) {
    next = Math.floor(Math.random() * state.queue.length);
  } else if (next >= state.queue.length) {
    if (state.repeatMode === "all") next = 0;
    else {
      state.isPlaying = false;
      updateBarNowPlaying();
      return;
    }
  }
  state.currentIndex = next;
  startTrack(state.queue[next]);
}

function playPrevTrack() {
  if (!state.queue.length) return;
  if (audioEl.currentTime > 3.5) {
    audioEl.currentTime = 0;
    return;
  }
  let prev = state.currentIndex - 1;
  if (prev < 0) prev = state.queue.length - 1;
  state.currentIndex = prev;
  startTrack(state.queue[prev]);
}

function updateTransportState() {
  document.getElementById("btn-shuffle").classList.toggle("active", state.isShuffle);
  document.getElementById("btn-fs-shuffle").classList.toggle("active", state.isShuffle);
  document.getElementById("btn-repeat").classList.toggle("active", state.repeatMode !== "off");
  document.getElementById("btn-fs-repeat").classList.toggle("active", state.repeatMode !== "off");
}

async function handlePickFolder() {
  const updated = await api.pickAndScanFolder();
  if (updated) {
    state.folders = updated.folders;
    state.tracks = updated.tracks;
    updateCountsAndBadges();
    renderMainWorkspace();
  }
}

/* CONFIGURABLE SPECTRUM VISUALIZER (BARS / WAVE / MIRROR) */
function startSpectrumLoop() {
  const canvas = document.getElementById("mini-spectrum");
  const ctx = canvas.getContext("2d");
  const freqData = new Uint8Array(64);
  const timeData = new Uint8Array(64);

  function draw() {
    requestAnimationFrame(draw);
    if (!state.settings.visualizerEnabled) return;

    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    if (!analyserNode || !state.isPlaying) {
      ctx.fillStyle = "#262626";
      for (let i = 0; i < 14; i++) {
        ctx.fillRect(i * 6 + 1, h - 2, 4, 2);
      }
      return;
    }

    const style = state.settings.visualizerStyle || "bars";
    const accColor = getAccentCssColor();
    ctx.fillStyle = accColor;
    ctx.strokeStyle = accColor;

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
  requestAnimationFrame(draw);
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

  const volPct = Math.round((state.settings.volume ?? 0.85) * 100);
  const volBar = document.getElementById("vol-bar");
  const fsVolBar = document.getElementById("fs-vol-bar");
  volBar.value = volPct;
  fsVolBar.value = volPct;
  updateSliderFill(volBar);
  updateSliderFill(fsVolBar);
  document.getElementById("vol-num").textContent = `${volPct}%`;
  audioEl.volume = state.settings.volume ?? 0.85;

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

  if (
    state.settings.outputDeviceId &&
    state.settings.outputDeviceId !== "default" &&
    typeof audioEl.setSinkId === "function"
  ) {
    audioEl.setSinkId(state.settings.outputDeviceId).catch(() => {});
  }

  applyAppearanceSettings();
  renderSidebarPlaylists();
  renderMainWorkspace();
  startSpectrumLoop();

  // Support drag & drop .flactheme anywhere into the window to apply a friend's theme
  document.addEventListener("dragover", (e) => {
    e.preventDefault();
  });
  document.addEventListener("drop", async (e) => {
    e.preventDefault();
    const file = e.dataTransfer?.files?.[0];
    if (file && file.path && file.name.toLowerCase().endsWith(".flactheme")) {
      const res = await api.importTheme(file.path);
      if (res && !res.error && res.settings) {
        state.settings = { ...state.settings, ...res.settings };
        applyAppearanceSettings();
        if (state.currentView === "settings") renderMainWorkspace();
      }
    }
  });

  // Register MPRIS / MediaSession Action Handlers for Linux / Noctalia
  if ("mediaSession" in navigator) {
    try {
      navigator.mediaSession.setActionHandler("play", () => {
        if (audioEl.paused) togglePlayPause();
      });
      navigator.mediaSession.setActionHandler("pause", () => {
        if (!audioEl.paused) togglePlayPause();
      });
      navigator.mediaSession.setActionHandler("previoustrack", () => playPrevTrack());
      navigator.mediaSession.setActionHandler("nexttrack", () => playNextTrack());
      navigator.mediaSession.setActionHandler("seekto", (details) => {
        if (details.fastSeek && "fastSeek" in audioEl) {
          audioEl.fastSeek(details.seekTime);
        } else if (Number.isFinite(details.seekTime)) {
          audioEl.currentTime = details.seekTime;
        }
        updateMprisPositionState();
        updateActiveLyricLine(true);
      });
      navigator.mediaSession.setActionHandler("seekbackward", (details) => {
        const offset = details.seekOffset || 10;
        audioEl.currentTime = Math.max(0, audioEl.currentTime - offset);
        updateMprisPositionState();
      });
      navigator.mediaSession.setActionHandler("seekforward", (details) => {
        const offset = details.seekOffset || 10;
        audioEl.currentTime = Math.min(audioEl.duration || 0, audioEl.currentTime + offset);
        updateMprisPositionState();
      });
    } catch (_) {}
  }

  window.addEventListener("click", () => {
    document.getElementById("playlist-popover").classList.add("hidden");
  });

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

  ["lyrics-container", "fs-lyrics-container"].forEach((id) => {
    const box = document.getElementById(id);
    box.addEventListener(
      "wheel",
      () => {
        state.manualLyricsScroll = true;
        box.classList.add("manual-scroll");
        clearTimeout(state.manualScrollTimer);
        state.manualScrollTimer = setTimeout(() => {
          state.manualLyricsScroll = false;
          box.classList.remove("manual-scroll");
          updateActiveLyricLine(true);
        }, 3000);
      },
      { passive: true }
    );
  });

  // Slide-up Fullscreen Player from bottom mini-player
  const openFullscreen = () => {
    document.body.classList.add("fs-open");
    setTimeout(() => updateActiveLyricLine(true), 80);
  };
  const closeFullscreen = () => {
    document.body.classList.remove("fs-open");
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
  document.getElementById("btn-next").onclick = playNextTrack;
  document.getElementById("btn-fs-next").onclick = playNextTrack;
  document.getElementById("btn-prev").onclick = playPrevTrack;
  document.getElementById("btn-fs-prev").onclick = playPrevTrack;

  document.getElementById("btn-fs-back-10").onclick = () => {
    audioEl.currentTime = Math.max(0, audioEl.currentTime - 10);
  };
  document.getElementById("btn-fs-fwd-10").onclick = () => {
    if (Number.isFinite(audioEl.duration)) {
      audioEl.currentTime = Math.min(audioEl.duration, audioEl.currentTime + 10);
    }
  };

  const toggleShuffle = () => {
    state.isShuffle = !state.isShuffle;
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
      const durMs = (audioEl.duration || state.currentTrack?.durationMs / 1000 || 0) * 1000;
      const previewMs = (parseFloat(e.target.value) / 1000) * durMs;
      document.getElementById("time-cur").textContent = formatTime(previewMs);
      document.getElementById("fs-time-cur").textContent = formatTime(previewMs);
    };
    bar.onchange = (e) => {
      const durSec = audioEl.duration || (state.currentTrack?.durationMs || 0) / 1000;
      if (durSec > 0) {
        audioEl.currentTime = (parseFloat(e.target.value) / 1000) * durSec;
      }
      state.isSeeking = false;
      updateMprisPositionState();
      updateActiveLyricLine(true);
    };
  });

  audioEl.addEventListener("timeupdate", () => {
    const curMs = audioEl.currentTime * 1000;
    const durMs =
      Number.isFinite(audioEl.duration) && audioEl.duration > 0
        ? audioEl.duration * 1000
        : state.currentTrack?.durationMs || 0;

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

    const cfMs = (state.settings.crossfadeSeconds || 0) * 1000;
    if (cfMs > 0 && durMs > cfMs * 2 && !audioEl.paused) {
      const rem = durMs - curMs;
      if (rem <= cfMs && rem > 0) {
        applyVolumeToAudioGraph(Math.max(0.05, rem / cfMs));
        if (rem <= 220 && state.crossfadeTriggeredForId !== state.currentTrack?.id) {
          state.crossfadeTriggeredForId = state.currentTrack?.id;
          playNextTrack();
          return;
        }
      } else if (curMs < cfMs) {
        applyVolumeToAudioGraph(Math.min(1.0, 0.2 + 0.8 * (curMs / cfMs)));
      } else {
        applyVolumeToAudioGraph(1.0);
      }
    } else {
      applyVolumeToAudioGraph(1.0);
    }

    updateActiveLyricLine(false);
  });

  audioEl.addEventListener("ended", () => {
    playNextTrack();
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
    api.saveSettings(state.settings);
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
      document.getElementById("playlist-popover").classList.add("hidden");
    } else if (e.code === "ArrowRight") {
      audioEl.currentTime = Math.min(audioEl.duration || 0, audioEl.currentTime + 5);
    } else if (e.code === "ArrowLeft") {
      audioEl.currentTime = Math.max(0, audioEl.currentTime - 5);
    }
  });

  api.onMediaAction((action) => {
    if (action === "toggle") togglePlayPause();
    else if (action === "next") playNextTrack();
    else if (action === "prev") playPrevTrack();
  });
});
