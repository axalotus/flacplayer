#pragma once
#include <windows.h>
#include <d2d1.h>
#include <d2d1helper.h>
#include <dwrite.h>
#include <wincodec.h>
#include <string>
#include <vector>
#include "AudioEngineWin.h"
#include "LibraryManager.h"
#include "Icons.h"
#include "AlbumArtExtractor.h"

// ── Nav screens ──────────────────────────────────────────────────────────
enum class Screen {
    AllTracks, Albums, AlbumDetail,
    Artists, ArtistDetail,
    Folders, Favorites, Hidden,
    PlaylistDetail, Settings
};

// ── Palette ──────────────────────────────────────────────────────────────
namespace Pal {
    static constexpr UINT32 Bg           = 0x111111;
    static constexpr UINT32 Surface      = 0x1A1A1A;
    static constexpr UINT32 Card         = 0x1E1E1E;
    static constexpr UINT32 CardHover    = 0x272727;
    static constexpr UINT32 CardActive   = 0x163325;
    static constexpr UINT32 NavPill      = 0x1C3D2F;
    static constexpr UINT32 Accent       = 0x4ECCA3;
    static constexpr UINT32 AccentDim    = 0x39A882;
    static constexpr UINT32 AccentBg     = 0x0E2922;
    static constexpr UINT32 TextPrimary  = 0xEEEEEE;
    static constexpr UINT32 TextSecondary= 0x9A9A9A;
    static constexpr UINT32 Divider      = 0x2A2A2A;
    static constexpr UINT32 HiRes       = 0xF4A044;
    static constexpr UINT32 Red         = 0xE53935;
    static constexpr UINT32 Dark        = 0x090909;
}

class MainWindowD2D {
public:
    static MainWindowD2D& getInstance();
    bool create(HINSTANCE hInstance, int width = 1200, int height = 780);
    HWND getHwnd() const { return m_hwnd; }

private:
    MainWindowD2D();
    ~MainWindowD2D();

    static LRESULT CALLBACK WndProc(HWND, UINT, WPARAM, LPARAM);
    LRESULT handleMsg(HWND, UINT, WPARAM, LPARAM);

    HRESULT createDevRes();
    void    discardDevRes();
    void    onPaint();
    void    render();

    // ── Section renderers ──────────────────────────────────────────────
    void renderSidebar(float x, float y, float w, float h);
    void renderContent(float x, float y, float w, float h);

    void renderAllTracks(float x, float y, float w, float h);
    void renderAlbumsGrid(float x, float y, float w, float h);
    void renderAlbumDetail(float x, float y, float w, float h);
    void renderArtistList(float x, float y, float w, float h);
    void renderArtistDetail(float x, float y, float w, float h);
    void renderFolders(float x, float y, float w, float h);
    void renderFavorites(float x, float y, float w, float h);
    void renderHidden(float x, float y, float w, float h);
    void renderPlaylistDetail(float x, float y, float w, float h);
    void renderSettings(float x, float y, float w, float h);
    void renderPlayerBar(float x, float y, float w, float h);

    // ── Common helpers ─────────────────────────────────────────────────
    void renderTrackRow(float x, float y, float w, float rowH,
                        size_t realIdx, bool isPlaying, bool isHover);
    void renderEmptyState(float cx, float cy, const wchar_t* title, const wchar_t* sub);
    void renderScanProgress(float x, float y, float w);

    void drawText(const std::wstring& t, IDWriteTextFormat* fmt,
                  float x, float y, float w, float h, ID2D1Brush* br,
                  DWRITE_TEXT_ALIGNMENT ha = DWRITE_TEXT_ALIGNMENT_LEADING,
                  DWRITE_PARAGRAPH_ALIGNMENT va = DWRITE_PARAGRAPH_ALIGNMENT_CENTER);

    // ── Input handlers ─────────────────────────────────────────────────
    void onLButtonDown(int x, int y);
    void onRButtonDown(int x, int y);
    void onMouseMove(int x, int y);
    void onLButtonUp(int x, int y);
    void onMouseWheel(int delta);
    void onKeyDown(WPARAM key);
    void onChar(wchar_t ch);
    void onDropFiles(HDROP hDrop);

    void showContextMenu(int x, int y, size_t trackIdx);
    void addFolderDialog();
    void promptCreatePlaylist();
    void promptRenamePlaylist(const std::string& id);
    void navigateTo(Screen scr);

    // ── D2D resources ──────────────────────────────────────────────────
    HWND m_hwnd = nullptr;
    HINSTANCE m_hInstance = nullptr;

    ID2D1Factory*           m_d2d   = nullptr;
    IDWriteFactory*         m_dw    = nullptr;
    IWICImagingFactory*     m_wic   = nullptr;
    ID2D1HwndRenderTarget*  m_rt    = nullptr;

    // Text formats
    IDWriteTextFormat* m_fmtLogo     = nullptr; // 17px Bold
    IDWriteTextFormat* m_fmtSectionHdr= nullptr; // 10px Bold Caps
    IDWriteTextFormat* m_fmtNavItem  = nullptr; // 13px Medium
    IDWriteTextFormat* m_fmtHeader   = nullptr; // 22px Bold
    IDWriteTextFormat* m_fmtTitle    = nullptr; // 14px SemiBold
    IDWriteTextFormat* m_fmtBody     = nullptr; // 12px Regular
    IDWriteTextFormat* m_fmtCaption  = nullptr; // 10px Bold (badges)
    IDWriteTextFormat* m_fmtSearch   = nullptr; // 13px Regular

    // Brushes (named after palette keys)
    ID2D1SolidColorBrush* m_brBg        = nullptr;
    ID2D1SolidColorBrush* m_brSurface   = nullptr;
    ID2D1SolidColorBrush* m_brCard      = nullptr;
    ID2D1SolidColorBrush* m_brCardHover = nullptr;
    ID2D1SolidColorBrush* m_brCardActive= nullptr;
    ID2D1SolidColorBrush* m_brNavPill   = nullptr;
    ID2D1SolidColorBrush* m_brAccent    = nullptr;
    ID2D1SolidColorBrush* m_brAccentDim = nullptr;
    ID2D1SolidColorBrush* m_brAccentBg  = nullptr;
    ID2D1SolidColorBrush* m_brText      = nullptr;
    ID2D1SolidColorBrush* m_brTextDim   = nullptr;
    ID2D1SolidColorBrush* m_brDivider   = nullptr;
    ID2D1SolidColorBrush* m_brHiRes     = nullptr;
    ID2D1SolidColorBrush* m_brRed       = nullptr;
    ID2D1SolidColorBrush* m_brDark      = nullptr;

    // ── Navigation / view state ────────────────────────────────────────
    Screen m_screen = Screen::AllTracks;
    std::string m_detailAlbum;    // which album is being drilled into
    std::string m_detailArtist;   // which artist
    std::string m_detailPlaylist; // which playlist id

    int  m_scrollOffset = 0;
    int  m_hoverRow     = -1;
    bool m_searchFocused = false;
    std::wstring m_searchW;

    bool  m_seekDragging = false;
    bool  m_volDragging  = false;

    // Layout constants
    static constexpr float SIDEBAR_W  = 234.0f;
    static constexpr float PLAYER_H   = 96.0f;
    static constexpr float ROW_H      = 62.0f;
    static constexpr float ROW_GAP    = 5.0f;
    static constexpr float PAD        = 24.0f;
    static constexpr float ART_SZ     = 46.0f;
    static constexpr float CARD_R     = 8.0f;
};
