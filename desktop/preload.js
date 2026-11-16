const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("flacApi", {
  getInitialState: () => ipcRenderer.invoke("get-initial-state"),
  pickAndScanFolder: () => ipcRenderer.invoke("pick-and-scan-folder"),
  rescanFolders: () => ipcRenderer.invoke("rescan-folders"),
  removeFolder: (folder) => ipcRenderer.invoke("remove-folder", folder),
  loadLyrics: (payload) => ipcRenderer.invoke("load-lyrics", payload),
  getCoverDataUrl: (coverPath) => ipcRenderer.invoke("get-cover-data-url", coverPath),
  pickLrcFile: () => ipcRenderer.invoke("pick-lrc-file"),
  pickBackgroundFile: () => ipcRenderer.invoke("pick-background-file"),
  recordTrackPlayed: (trackId) => ipcRenderer.invoke("record-track-played", trackId),
  clearRecentlyPlayed: () => ipcRenderer.invoke("clear-recently-played"),
  saveLyricOffset: (trackId, offsetMs) =>
    ipcRenderer.invoke("save-lyric-offset", { trackId, offsetMs }),
  toggleFavorite: (trackId) => ipcRenderer.invoke("toggle-favorite", trackId),
  createPlaylist: (name) => ipcRenderer.invoke("create-playlist", name),
  deletePlaylist: (playlistId) => ipcRenderer.invoke("delete-playlist", playlistId),
  addTrackToPlaylist: (playlistId, trackId) =>
    ipcRenderer.invoke("add-track-to-playlist", { playlistId, trackId }),
  saveEqConfig: (eqConfig) => ipcRenderer.invoke("save-eq-config", eqConfig),
  saveSettings: (settings) => ipcRenderer.invoke("save-settings", settings),
  exportTheme: (themeName) => ipcRenderer.invoke("export-theme", themeName),
  importTheme: (filePath) => ipcRenderer.invoke("import-theme", filePath),
  onMediaAction: (cb) => {
    ipcRenderer.on("media-action", (_, action) => cb(action));
  },
});
