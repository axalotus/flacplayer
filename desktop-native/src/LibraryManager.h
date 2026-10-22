#pragma once
#include <string>
#include <vector>
#include <set>
#include <map>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <atomic>
#include <functional>
#include "AudioEngineWin.h"

enum class RepeatMode { OFF, ALL, ONE };

// ── Data Structures ────────────────────────────────────────────────────────

struct Playlist {
    std::string id;
    std::string name;
    std::vector<std::string> trackPaths;
};

struct AlbumInfo {
    std::string name;          // album title
    std::string artist;        // primary artist
    std::string samplePath;    // first track path (for art)
    std::vector<size_t> indices; // indices into m_tracks
};

struct ArtistInfo {
    std::string name;
    int albumCount  = 0;
    int trackCount  = 0;
    std::string samplePath;
    std::vector<std::string> albums;  // album names
    std::vector<size_t> indices;      // all track indices
};

// ── LibraryManager ────────────────────────────────────────────────────────

class LibraryManager {
public:
    static LibraryManager& getInstance();

    // ── Scanning ────────────────────────────────────────────────────────
    // Async: runs in background thread; UI should poll isScanningActive()/getScanProgress()
    void scanDirectoryAsync(const std::string& dirPath, std::function<void()> onComplete = nullptr);
    // Sync (called internally or for single file drop)
    bool addTrack(const std::string& filePath);
    void clearLibrary();
    void rescan(); // rescan all autoScanFolders

    bool  isScanningActive()  const { return m_scanning.load(); }
    int   getScanScanned()    const { return m_scanScanned.load(); }
    int   getScanTotal()      const { return m_scanTotal.load(); }

    // ── Querying ─────────────────────────────────────────────────────────
    // All tracks (read with shared lock)
    std::vector<TrackInfo> getTracksCopy() const;
    size_t                 getTrackCount() const;

    // Filtered & sorted view (for virtual scroll)
    std::vector<size_t> getFilteredIndices(const std::string& query = "") const;

    // Albums / Artists (pre-computed after scan)
    const std::vector<AlbumInfo>&  getAlbums()  const { return m_albums;  }
    const std::vector<ArtistInfo>& getArtists() const { return m_artists; }

    // Search
    void setSearchQuery(const std::string& q);
    const std::string& getSearchQuery() const { return m_searchQuery; }

    // ── Playlists ────────────────────────────────────────────────────────
    const std::vector<Playlist>& getPlaylists() const { return m_playlists; }
    std::string createPlaylist(const std::string& name);
    bool renamePlaylist(const std::string& id, const std::string& newName);
    bool deletePlaylist(const std::string& id);
    bool addTrackToPlaylist(const std::string& pid, const std::string& path);
    bool removeTrackFromPlaylist(const std::string& pid, const std::string& path);
    const Playlist* getPlaylistById(const std::string& id) const;

    // ── Favorites / Hidden ───────────────────────────────────────────────
    void toggleFavorite(const std::string& path);
    bool isFavorite(const std::string& path) const;
    std::vector<size_t> getFavoriteIndices() const;

    void toggleHidden(const std::string& path);
    bool isHidden(const std::string& path) const;
    std::vector<size_t> getHiddenIndices() const;

    // ── Playback ─────────────────────────────────────────────────────────
    void playIndex(size_t idx);
    void playIndices(const std::vector<size_t>& idxList, size_t startPos);
    void playNext();
    void playPrevious();
    void toggleShuffle();
    void toggleRepeat();

    bool       isShuffle()      const { return m_shuffle; }
    RepeatMode getRepeatMode()  const { return m_repeatMode; }
    int        getCurrentIndex() const { return m_currentIndex; }

    // ── Settings ─────────────────────────────────────────────────────────
    const std::vector<std::string>& getAutoScanFolders() const { return m_autoScanFolders; }
    void addAutoScanFolder(const std::string& path);
    void removeAutoScanFolder(const std::string& path);

    int  getMinDurationSec() const { return m_minDurationSec; }
    void setMinDurationSec(int s)  { m_minDurationSec = s; saveState(); }

    // ── Persistence ──────────────────────────────────────────────────────
    void saveState();
    void loadState();

private:
    LibraryManager();
    ~LibraryManager();

    void scanWorker(const std::string& root, std::function<void()> onComplete);
    void rebuildGroupings();   // called after scan completes
    std::string getAppDataPath() const;

    // Track storage (guarded by m_tracksMx — shared_mutex for concurrent reads)
    mutable std::shared_mutex   m_tracksMx;
    std::vector<TrackInfo>      m_tracks;
    std::set<std::string>       m_pathSet; // dedup

    // Derived views (rebuilt after scan — guarded by m_tracksMx write lock)
    std::vector<AlbumInfo>   m_albums;
    std::vector<ArtistInfo>  m_artists;

    // Scan state
    std::atomic<bool>  m_scanning{false};
    std::atomic<int>   m_scanScanned{0};
    std::atomic<int>   m_scanTotal{0};
    std::thread        m_scanThread;

    // Other data (guarded by m_dataMx)
    mutable std::mutex           m_dataMx;
    std::vector<Playlist>        m_playlists;
    std::set<std::string>        m_favorites;
    std::set<std::string>        m_hiddenTracks;
    std::vector<std::string>     m_autoScanFolders;
    std::string                  m_searchQuery;

    // Playback queue
    std::vector<size_t> m_playQueue;
    int  m_queuePos     = -1;
    int  m_currentIndex = -1;

    bool       m_shuffle    = false;
    RepeatMode m_repeatMode = RepeatMode::OFF;
    int        m_minDurationSec = 0;
};
