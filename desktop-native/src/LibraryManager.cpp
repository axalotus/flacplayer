#include "LibraryManager.h"
#include <windows.h>
#include <shlobj.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <random>
#include <chrono>

namespace fs = std::filesystem;

// ── Singleton ─────────────────────────────────────────────────────────────

LibraryManager& LibraryManager::getInstance() {
    static LibraryManager inst; return inst;
}

LibraryManager::LibraryManager() { loadState(); }
LibraryManager::~LibraryManager() { saveState(); if (m_scanThread.joinable()) m_scanThread.detach(); }

// ── Helpers ───────────────────────────────────────────────────────────────

static std::string toUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(n - 1, 0);
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, &s[0], n, nullptr, nullptr);
    return s;
}

static std::wstring toWide(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    if (n <= 0) { // try ACP
        n = MultiByteToWideChar(CP_ACP, 0, s.c_str(), -1, nullptr, 0);
        if (n <= 0) return {};
        std::wstring w(n - 1, 0);
        MultiByteToWideChar(CP_ACP, 0, s.c_str(), -1, &w[0], n);
        return w;
    }
    std::wstring w(n - 1, 0);
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, &w[0], n);
    return w;
}

static std::string trimstr(const std::string& s) {
    size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return {};
    size_t b = s.find_last_not_of(" \t\r\n");
    return s.substr(a, b - a + 1);
}

static bool isAudioExtension(const fs::path& p) {
    auto ext = p.extension().wstring();
    std::transform(ext.begin(), ext.end(), ext.begin(), ::towlower);
    return ext == L".flac" || ext == L".mp3" || ext == L".wav";
}

std::string LibraryManager::getAppDataPath() const {
    char path[MAX_PATH]{};
    if (SUCCEEDED(SHGetFolderPathA(NULL, CSIDL_APPDATA, NULL, 0, path))) {
        std::string dir = std::string(path) + "\\FlacPlayer";
        CreateDirectoryA(dir.c_str(), NULL);
        return dir;
    }
    return ".";
}

// ── Track Adding ──────────────────────────────────────────────────────────

bool LibraryManager::addTrack(const std::string& filePath) {
    {
        std::shared_lock<std::shared_mutex> rl(m_tracksMx);
        if (m_pathSet.count(filePath)) return false;
    }

    AudioDecoder dec;
    if (!dec.openFile(filePath)) return false;

    int64_t durMs = dec.getDurationMs();
    if (m_minDurationSec > 0 && durMs < m_minDurationSec * 1000LL) return false;

    TrackInfo ti;
    ti.filePath   = filePath;
    ti.sampleRate = dec.getSampleRate();
    ti.channels   = dec.getChannels();
    ti.bitDepth   = dec.getBitDepth();
    ti.durationMs = durMs;
    ti.format     = dec.getFormatString();
    ti.isHiRes    = dec.isHiRes();

    // Filename-based title/artist/album
    fs::path p = toWide(filePath);
    std::string base = toUtf8(p.stem().wstring());
    std::string folder = toUtf8(p.parent_path().filename().wstring());

    size_t sep = base.find(" - ");
    if (sep != std::string::npos) {
        ti.artist = trimstr(base.substr(0, sep));
        ti.title  = trimstr(base.substr(sep + 3));
    } else {
        ti.artist = "Unknown Artist";
        ti.title  = base;
    }
    ti.album = folder.empty() ? "Unknown Album" : folder;

    std::unique_lock<std::shared_mutex> wl(m_tracksMx);
    m_pathSet.insert(filePath);
    m_tracks.push_back(std::move(ti));
    return true;
}

// ── Async Scanning ────────────────────────────────────────────────────────

void LibraryManager::scanDirectoryAsync(const std::string& dirPath,
                                        std::function<void()> onComplete) {
    if (m_scanning.load()) return; // one scan at a time
    if (m_scanThread.joinable()) m_scanThread.detach();

    m_scanning.store(true);
    m_scanScanned.store(0);
    m_scanTotal.store(0);

    m_scanThread = std::thread([this, dirPath, onComplete]() {
        scanWorker(dirPath, onComplete);
    });
}

void LibraryManager::scanWorker(const std::string& root,
                                std::function<void()> onComplete) {
    fs::path rootPath = toWide(root);

    // First pass: count files for progress (non-throwing)
    int total = 0;
    {
        std::error_code ec;
        fs::recursive_directory_iterator it(rootPath, fs::directory_options::skip_permission_denied, ec);
        if (!ec) {
            for (auto& entry : it) {
                std::error_code ec2;
                if (entry.is_regular_file(ec2) && isAudioExtension(entry.path())) ++total;
            }
        }
    }
    m_scanTotal.store(total);

    // Second pass: process
    std::error_code ec;
    fs::recursive_directory_iterator it(rootPath, fs::directory_options::skip_permission_denied, ec);
    if (!ec) {
        for (auto& entry : it) {
            std::error_code ec2;
            if (!entry.is_regular_file(ec2)) continue;
            if (!isAudioExtension(entry.path())) continue;

            std::string utf8path = toUtf8(entry.path().wstring());
            addTrack(utf8path);
            m_scanScanned.fetch_add(1);
        }
    }

    // Auto-add root folder to scan list
    {
        std::lock_guard<std::mutex> lk(m_dataMx);
        if (std::find(m_autoScanFolders.begin(), m_autoScanFolders.end(), root)
            == m_autoScanFolders.end())
            m_autoScanFolders.push_back(root);
    }

    rebuildGroupings();
    saveState();
    m_scanning.store(false);
    if (onComplete) onComplete();
}

void LibraryManager::rescan() {
    std::vector<std::string> folders;
    { std::lock_guard<std::mutex> lk(m_dataMx); folders = m_autoScanFolders; }
    for (auto& f : folders) scanDirectoryAsync(f, nullptr);
}

void LibraryManager::clearLibrary() {
    {
        std::unique_lock<std::shared_mutex> wl(m_tracksMx);
        m_tracks.clear(); m_pathSet.clear();
        m_albums.clear(); m_artists.clear();
    }
    m_currentIndex = -1; m_queuePos = -1; m_playQueue.clear();
    saveState();
}

// ── Groupings ─────────────────────────────────────────────────────────────

void LibraryManager::rebuildGroupings() {
    // Compute under write lock so reads wait
    std::unique_lock<std::shared_mutex> wl(m_tracksMx);

    // Build album map: key = "artist\nalbum"
    std::map<std::string, AlbumInfo> albumMap;
    for (size_t i = 0; i < m_tracks.size(); ++i) {
        const auto& t = m_tracks[i];
        std::string key = t.artist + "\n" + t.album;
        auto& ai = albumMap[key];
        ai.name   = t.album;
        ai.artist = t.artist;
        if (ai.samplePath.empty()) ai.samplePath = t.filePath;
        ai.indices.push_back(i);
    }
    m_albums.clear();
    for (auto& [k, v] : albumMap) m_albums.push_back(std::move(v));
    std::sort(m_albums.begin(), m_albums.end(),
              [](const AlbumInfo& a, const AlbumInfo& b) { return a.name < b.name; });

    // Build artist map
    std::map<std::string, ArtistInfo> artistMap;
    for (size_t i = 0; i < m_tracks.size(); ++i) {
        const auto& t = m_tracks[i];
        auto& ai = artistMap[t.artist];
        ai.name = t.artist;
        if (ai.samplePath.empty()) ai.samplePath = t.filePath;
        ai.indices.push_back(i);
        if (std::find(ai.albums.begin(), ai.albums.end(), t.album) == ai.albums.end())
            ai.albums.push_back(t.album);
    }
    m_artists.clear();
    for (auto& [k, v] : artistMap) {
        v.albumCount = (int)v.albums.size();
        v.trackCount = (int)v.indices.size();
        m_artists.push_back(std::move(v));
    }
    std::sort(m_artists.begin(), m_artists.end(),
              [](const ArtistInfo& a, const ArtistInfo& b) { return a.name < b.name; });
}

// ── Querying ──────────────────────────────────────────────────────────────

std::vector<TrackInfo> LibraryManager::getTracksCopy() const {
    std::shared_lock<std::shared_mutex> rl(m_tracksMx);
    return m_tracks;
}

size_t LibraryManager::getTrackCount() const {
    std::shared_lock<std::shared_mutex> rl(m_tracksMx);
    return m_tracks.size();
}

std::vector<size_t> LibraryManager::getFilteredIndices(const std::string& query) const {
    std::shared_lock<std::shared_mutex> rl(m_tracksMx);
    std::vector<size_t> result;

    std::string qlo = query;
    std::transform(qlo.begin(), qlo.end(), qlo.begin(), ::tolower);

    std::lock_guard<std::mutex> lk2(m_dataMx);
    for (size_t i = 0; i < m_tracks.size(); ++i) {
        if (m_hiddenTracks.count(m_tracks[i].filePath)) continue;

        if (qlo.empty()) {
            result.push_back(i);
        } else {
            std::string t = m_tracks[i].title + " " + m_tracks[i].artist + " " + m_tracks[i].album;
            std::transform(t.begin(), t.end(), t.begin(), ::tolower);
            if (t.find(qlo) != std::string::npos) result.push_back(i);
        }
    }
    return result;
}

void LibraryManager::setSearchQuery(const std::string& q) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    m_searchQuery = q;
}

// ── Playlists ─────────────────────────────────────────────────────────────

std::string LibraryManager::createPlaylist(const std::string& name) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    Playlist pl;
    pl.id   = "pl_" + std::to_string(GetTickCount64());
    pl.name = name.empty() ? "New Playlist" : name;
    m_playlists.push_back(pl);
    saveState();
    return pl.id;
}

bool LibraryManager::renamePlaylist(const std::string& id, const std::string& nm) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    for (auto& pl : m_playlists) if (pl.id == id) { pl.name = nm; saveState(); return true; }
    return false;
}

bool LibraryManager::deletePlaylist(const std::string& id) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    for (auto it = m_playlists.begin(); it != m_playlists.end(); ++it)
        if (it->id == id) { m_playlists.erase(it); saveState(); return true; }
    return false;
}

bool LibraryManager::addTrackToPlaylist(const std::string& pid, const std::string& path) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    for (auto& pl : m_playlists)
        if (pl.id == pid) {
            if (std::find(pl.trackPaths.begin(), pl.trackPaths.end(), path) == pl.trackPaths.end())
                pl.trackPaths.push_back(path);
            saveState(); return true;
        }
    return false;
}

bool LibraryManager::removeTrackFromPlaylist(const std::string& pid, const std::string& path) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    for (auto& pl : m_playlists)
        if (pl.id == pid) {
            auto it = std::find(pl.trackPaths.begin(), pl.trackPaths.end(), path);
            if (it != pl.trackPaths.end()) { pl.trackPaths.erase(it); saveState(); return true; }
        }
    return false;
}

const Playlist* LibraryManager::getPlaylistById(const std::string& id) const {
    std::lock_guard<std::mutex> lk(m_dataMx);
    for (const auto& pl : m_playlists) if (pl.id == id) return &pl;
    return nullptr;
}

// ── Favorites / Hidden ────────────────────────────────────────────────────

void LibraryManager::toggleFavorite(const std::string& path) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    if (m_favorites.count(path)) m_favorites.erase(path); else m_favorites.insert(path);
    saveState();
}
bool LibraryManager::isFavorite(const std::string& path) const {
    std::lock_guard<std::mutex> lk(m_dataMx); return m_favorites.count(path) > 0;
}
std::vector<size_t> LibraryManager::getFavoriteIndices() const {
    std::shared_lock<std::shared_mutex> rl(m_tracksMx);
    std::lock_guard<std::mutex> lk(m_dataMx);
    std::vector<size_t> r;
    for (size_t i = 0; i < m_tracks.size(); ++i)
        if (m_favorites.count(m_tracks[i].filePath) && !m_hiddenTracks.count(m_tracks[i].filePath))
            r.push_back(i);
    return r;
}

void LibraryManager::toggleHidden(const std::string& path) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    if (m_hiddenTracks.count(path)) m_hiddenTracks.erase(path); else m_hiddenTracks.insert(path);
    saveState();
}
bool LibraryManager::isHidden(const std::string& path) const {
    std::lock_guard<std::mutex> lk(m_dataMx); return m_hiddenTracks.count(path) > 0;
}
std::vector<size_t> LibraryManager::getHiddenIndices() const {
    std::shared_lock<std::shared_mutex> rl(m_tracksMx);
    std::lock_guard<std::mutex> lk(m_dataMx);
    std::vector<size_t> r;
    for (size_t i = 0; i < m_tracks.size(); ++i)
        if (m_hiddenTracks.count(m_tracks[i].filePath)) r.push_back(i);
    return r;
}

// ── Playback ──────────────────────────────────────────────────────────────

void LibraryManager::playIndex(size_t idx) {
    {
        std::shared_lock<std::shared_mutex> rl(m_tracksMx);
        if (idx >= m_tracks.size()) return;
    }
    m_currentIndex = (int)idx;
    m_playQueue.clear();
    {
        std::shared_lock<std::shared_mutex> rl(m_tracksMx);
        std::lock_guard<std::mutex> lk(m_dataMx);
        for (size_t i = 0; i < m_tracks.size(); ++i)
            if (!m_hiddenTracks.count(m_tracks[i].filePath)) m_playQueue.push_back(i);
    }
    auto it = std::find(m_playQueue.begin(), m_playQueue.end(), idx);
    m_queuePos = (it != m_playQueue.end()) ? (int)(it - m_playQueue.begin()) : 0;

    std::string path;
    { std::shared_lock<std::shared_mutex> rl(m_tracksMx); path = m_tracks[idx].filePath; }
    AudioEngineWin::getInstance().loadAndPlay(path);

    // Copy metadata to AudioEngine track
    {
        std::shared_lock<std::shared_mutex> rl(m_tracksMx);
        auto& eng = AudioEngineWin::getInstance();
        // We'll update title/artist from lib since engine parses filename only
    }
}

void LibraryManager::playIndices(const std::vector<size_t>& list, size_t startPos) {
    if (startPos >= list.size()) return;
    m_playQueue = list;
    m_queuePos  = (int)startPos;
    m_currentIndex = (int)list[startPos];
    std::string path;
    { std::shared_lock<std::shared_mutex> rl(m_tracksMx); path = m_tracks[m_currentIndex].filePath; }
    AudioEngineWin::getInstance().loadAndPlay(path);
}

void LibraryManager::playNext() {
    if (m_playQueue.empty()) return;
    if (m_repeatMode == RepeatMode::ONE) {
        std::string path;
        { std::shared_lock<std::shared_mutex> rl(m_tracksMx); path = m_tracks[m_currentIndex].filePath; }
        AudioEngineWin::getInstance().loadAndPlay(path);
        return;
    }
    if (m_shuffle) {
        static std::mt19937 rng(std::random_device{}());
        m_queuePos = std::uniform_int_distribution<int>(0, (int)m_playQueue.size()-1)(rng);
    } else {
        m_queuePos = (m_queuePos + 1) % (int)m_playQueue.size();
    }
    m_currentIndex = (int)m_playQueue[m_queuePos];
    std::string path;
    { std::shared_lock<std::shared_mutex> rl(m_tracksMx); path = m_tracks[m_currentIndex].filePath; }
    AudioEngineWin::getInstance().loadAndPlay(path);
}

void LibraryManager::playPrevious() {
    if (m_playQueue.empty()) return;
    if (AudioEngineWin::getInstance().getPositionMs() > 3000) {
        AudioEngineWin::getInstance().seekTo(0); return;
    }
    m_queuePos = (m_queuePos > 0) ? m_queuePos - 1 : (int)m_playQueue.size() - 1;
    m_currentIndex = (int)m_playQueue[m_queuePos];
    std::string path;
    { std::shared_lock<std::shared_mutex> rl(m_tracksMx); path = m_tracks[m_currentIndex].filePath; }
    AudioEngineWin::getInstance().loadAndPlay(path);
}

void LibraryManager::toggleShuffle() { m_shuffle = !m_shuffle; }
void LibraryManager::toggleRepeat()  {
    m_repeatMode = (RepeatMode)(((int)m_repeatMode + 1) % 3);
}

// ── Settings ──────────────────────────────────────────────────────────────

void LibraryManager::addAutoScanFolder(const std::string& path) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    if (std::find(m_autoScanFolders.begin(), m_autoScanFolders.end(), path) == m_autoScanFolders.end())
        m_autoScanFolders.push_back(path);
    saveState();
}

void LibraryManager::removeAutoScanFolder(const std::string& path) {
    std::lock_guard<std::mutex> lk(m_dataMx);
    auto it = std::find(m_autoScanFolders.begin(), m_autoScanFolders.end(), path);
    if (it != m_autoScanFolders.end()) { m_autoScanFolders.erase(it); saveState(); }
}

// ── Persistence ───────────────────────────────────────────────────────────

void LibraryManager::saveState() {
    std::string dir = getAppDataPath();

    // Tracks
    {
        std::shared_lock<std::shared_mutex> rl(m_tracksMx);
        std::ofstream f(dir + "\\tracks.txt");
        for (const auto& t : m_tracks) f << t.filePath << "\n";
    }

    // AutoScan folders, favorites, hidden, playlists, config
    std::lock_guard<std::mutex> lk(m_dataMx);

    { std::ofstream f(dir + "\\folders.txt"); for (const auto& s : m_autoScanFolders) f << s << "\n"; }
    { std::ofstream f(dir + "\\favorites.txt"); for (const auto& s : m_favorites) f << s << "\n"; }
    { std::ofstream f(dir + "\\hidden.txt"); for (const auto& s : m_hiddenTracks) f << s << "\n"; }
    {
        std::ofstream f(dir + "\\playlists.txt");
        for (const auto& pl : m_playlists) {
            f << "[PL]\n" << pl.id << "\n" << pl.name << "\n" << pl.trackPaths.size() << "\n";
            for (const auto& p : pl.trackPaths) f << p << "\n";
        }
    }
    {
        std::ofstream f(dir + "\\config.txt");
        f << "min_dur=" << m_minDurationSec << "\n";
    }
}

void LibraryManager::loadState() {
    std::string dir = getAppDataPath();
    std::string line;

    // Config first
    {
        std::ifstream f(dir + "\\config.txt");
        while (std::getline(f, line)) {
            if (line.rfind("min_dur=", 0) == 0) m_minDurationSec = std::stoi(line.substr(8));
        }
    }

    // AutoScan folders
    {
        std::ifstream f(dir + "\\folders.txt");
        while (std::getline(f, line)) if (!line.empty()) m_autoScanFolders.push_back(line);
    }

    // Tracks
    {
        std::ifstream f(dir + "\\tracks.txt");
        while (std::getline(f, line)) if (!line.empty()) addTrack(line);
    }

    // Re-scan auto folders in background
    if (!m_autoScanFolders.empty()) {
        std::vector<std::string> folders = m_autoScanFolders;
        for (const auto& fld : folders) scanDirectoryAsync(fld, nullptr);
    } else {
        rebuildGroupings();
    }

    // Favorites
    { std::ifstream f(dir + "\\favorites.txt"); while (std::getline(f, line)) if (!line.empty()) m_favorites.insert(line); }
    // Hidden
    { std::ifstream f(dir + "\\hidden.txt"); while (std::getline(f, line)) if (!line.empty()) m_hiddenTracks.insert(line); }

    // Playlists
    {
        std::ifstream f(dir + "\\playlists.txt");
        while (std::getline(f, line)) {
            if (line != "[PL]") continue;
            Playlist pl;
            std::getline(f, pl.id);
            std::getline(f, pl.name);
            std::string cntStr; std::getline(f, cntStr);
            int cnt = 0;
            try { cnt = std::stoi(cntStr); } catch(...) {}
            for (int i = 0; i < cnt; ++i) {
                std::string p; std::getline(f, p);
                if (!p.empty()) pl.trackPaths.push_back(p);
            }
            m_playlists.push_back(pl);
        }
    }
}
