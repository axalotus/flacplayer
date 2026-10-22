#include "MainWindowD2D.h"
#include <shlobj.h>
#include <sstream>
#include <iomanip>
#include <algorithm>

// ── String helpers ────────────────────────────────────────────────────────

static std::wstring W(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    if (n <= 0) { n = MultiByteToWideChar(CP_ACP,0,s.c_str(),-1,nullptr,0); if(n<=0)return {}; std::wstring w(n-1,0); MultiByteToWideChar(CP_ACP,0,s.c_str(),-1,&w[0],n); return w; }
    std::wstring w(n-1, 0); MultiByteToWideChar(CP_UTF8,0,s.c_str(),-1,&w[0],n); return w;
}
static std::string N(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8,0,w.c_str(),-1,nullptr,0,nullptr,nullptr);
    if (n<=0) return {};
    std::string s(n-1,0); WideCharToMultiByte(CP_UTF8,0,w.c_str(),-1,&s[0],n,nullptr,nullptr);
    return s;
}
static std::wstring fmtTime(int64_t ms) {
    int s = (int)(ms/1000), m = s/60; s %= 60;
    std::wostringstream o; o << m << L":" << std::setw(2) << std::setfill(L'0') << s; return o.str();
}

// ── Singleton ─────────────────────────────────────────────────────────────

MainWindowD2D& MainWindowD2D::getInstance() { static MainWindowD2D inst; return inst; }

MainWindowD2D::MainWindowD2D() {
    CoInitialize(nullptr);
    D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, &m_d2d);
    DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory),
                        reinterpret_cast<IUnknown**>(&m_dw));
    CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                     IID_PPV_ARGS(&m_wic));
}

MainWindowD2D::~MainWindowD2D() {
    discardDevRes();
    auto safe = [](auto*& p){ if(p){p->Release();p=nullptr;} };
    safe(m_fmtLogo); safe(m_fmtSectionHdr); safe(m_fmtNavItem);
    safe(m_fmtHeader); safe(m_fmtTitle); safe(m_fmtBody);
    safe(m_fmtCaption); safe(m_fmtSearch);
    safe(m_dw); safe(m_d2d); safe(m_wic);
    CoUninitialize();
}

// ── Device resources ──────────────────────────────────────────────────────

HRESULT MainWindowD2D::createDevRes() {
    if (m_rt) return S_OK;

    RECT rc; GetClientRect(m_hwnd, &rc);
    auto sz = D2D1::SizeU(rc.right - rc.left, rc.bottom - rc.top);

    HRESULT hr = m_d2d->CreateHwndRenderTarget(
        D2D1::RenderTargetProperties(
            D2D1_RENDER_TARGET_TYPE_DEFAULT,
            D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_PREMULTIPLIED)),
        D2D1::HwndRenderTargetProperties(m_hwnd, sz, D2D1_PRESENT_OPTIONS_IMMEDIATELY),
        &m_rt);
    if (FAILED(hr)) return hr;

    m_rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE);

    // Brushes
    auto mkBr = [&](UINT32 c, ID2D1SolidColorBrush** b) {
        m_rt->CreateSolidColorBrush(D2D1::ColorF(c), b);
    };
    mkBr(Pal::Bg,          &m_brBg);
    mkBr(Pal::Surface,     &m_brSurface);
    mkBr(Pal::Card,        &m_brCard);
    mkBr(Pal::CardHover,   &m_brCardHover);
    mkBr(Pal::CardActive,  &m_brCardActive);
    mkBr(Pal::NavPill,     &m_brNavPill);
    mkBr(Pal::Accent,      &m_brAccent);
    mkBr(Pal::AccentDim,   &m_brAccentDim);
    mkBr(Pal::AccentBg,    &m_brAccentBg);
    mkBr(Pal::TextPrimary, &m_brText);
    mkBr(Pal::TextSecondary,&m_brTextDim);
    mkBr(Pal::Divider,     &m_brDivider);
    mkBr(Pal::HiRes,       &m_brHiRes);
    mkBr(Pal::Red,         &m_brRed);
    mkBr(Pal::Dark,        &m_brDark);

    // Fonts: prefer Segoe UI Variable Text (Win11), fall back to Segoe UI
    const wchar_t* F = L"Segoe UI Variable Text";
    auto mkFmt = [&](IDWriteTextFormat** f, DWRITE_FONT_WEIGHT wt, float sz2) {
        if (FAILED(m_dw->CreateTextFormat(F,nullptr,wt,DWRITE_FONT_STYLE_NORMAL,
            DWRITE_FONT_STRETCH_NORMAL,sz2,L"ru-RU",f))) {
            m_dw->CreateTextFormat(L"Segoe UI",nullptr,wt,DWRITE_FONT_STYLE_NORMAL,
                DWRITE_FONT_STRETCH_NORMAL,sz2,L"ru-RU",f);
        }
    };
    mkFmt(&m_fmtLogo,      DWRITE_FONT_WEIGHT_BOLD,      17.0f);
    mkFmt(&m_fmtSectionHdr,DWRITE_FONT_WEIGHT_BOLD,      10.0f);
    mkFmt(&m_fmtNavItem,   DWRITE_FONT_WEIGHT_MEDIUM,    13.0f);
    mkFmt(&m_fmtHeader,    DWRITE_FONT_WEIGHT_BOLD,      22.0f);
    mkFmt(&m_fmtTitle,     DWRITE_FONT_WEIGHT_SEMI_BOLD, 14.0f);
    mkFmt(&m_fmtBody,      DWRITE_FONT_WEIGHT_NORMAL,    12.0f);
    mkFmt(&m_fmtCaption,   DWRITE_FONT_WEIGHT_BOLD,      10.0f);
    mkFmt(&m_fmtSearch,    DWRITE_FONT_WEIGHT_NORMAL,    13.0f);

    AlbumArtExtractor::getInstance().init(m_rt, m_wic);
    return S_OK;
}

void MainWindowD2D::discardDevRes() {
    auto safe = [](auto*& p){ if(p){p->Release();p=nullptr;} };
    safe(m_brBg); safe(m_brSurface); safe(m_brCard); safe(m_brCardHover);
    safe(m_brCardActive); safe(m_brNavPill); safe(m_brAccent); safe(m_brAccentDim);
    safe(m_brAccentBg); safe(m_brText); safe(m_brTextDim); safe(m_brDivider);
    safe(m_brHiRes); safe(m_brRed); safe(m_brDark);
    safe(m_rt);
    AlbumArtExtractor::getInstance().clearCache();
}

// ── Window creation ───────────────────────────────────────────────────────

bool MainWindowD2D::create(HINSTANCE hInst, int W, int H) {
    m_hInstance = hInst;

    WNDCLASSEXW wc{sizeof(wc)};
    wc.style        = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc  = WndProc;
    wc.hInstance    = hInst;
    wc.hCursor      = LoadCursor(nullptr, IDC_ARROW);
    wc.lpszClassName= L"FlacPlayerD2D";
    RegisterClassExW(&wc);

    int sx = (GetSystemMetrics(SM_CXSCREEN)-W)/2;
    int sy = (GetSystemMetrics(SM_CYSCREEN)-H)/2;

    m_hwnd = CreateWindowExW(WS_EX_ACCEPTFILES, L"FlacPlayerD2D",
        L"FlacPlayer — Hi-Res Music Player",
        WS_OVERLAPPEDWINDOW, sx, sy, W, H,
        nullptr, nullptr, hInst, this);
    if (!m_hwnd) return false;

    DragAcceptFiles(m_hwnd, TRUE);
    ShowWindow(m_hwnd, SW_SHOW);
    UpdateWindow(m_hwnd);
    SetTimer(m_hwnd, 1, 16, nullptr); // 60 fps timer
    return true;
}

LRESULT CALLBACK MainWindowD2D::WndProc(HWND hw, UINT msg, WPARAM wp, LPARAM lp) {
    MainWindowD2D* self = nullptr;
    if (msg == WM_CREATE) {
        self = reinterpret_cast<MainWindowD2D*>(((CREATESTRUCT*)lp)->lpCreateParams);
        SetWindowLongPtr(hw, GWLP_USERDATA, (LONG_PTR)self);
    } else self = reinterpret_cast<MainWindowD2D*>(GetWindowLongPtr(hw, GWLP_USERDATA));
    if (self) return self->handleMsg(hw, msg, wp, lp);
    return DefWindowProcW(hw, msg, wp, lp);
}

LRESULT MainWindowD2D::handleMsg(HWND hw, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_SIZE:
        if (m_rt) m_rt->Resize(D2D1::SizeU(LOWORD(lp), HIWORD(lp)));
        InvalidateRect(hw, nullptr, FALSE); return 0;
    case WM_PAINT: {
        PAINTSTRUCT ps; BeginPaint(hw, &ps);
        onPaint();
        EndPaint(hw, &ps); return 0; }
    case WM_ERASEBKGND: return 1;
    case WM_TIMER: InvalidateRect(hw, nullptr, FALSE); return 0;
    case WM_LBUTTONDOWN: onLButtonDown(LOWORD(lp), HIWORD(lp)); return 0;
    case WM_RBUTTONDOWN: onRButtonDown(LOWORD(lp), HIWORD(lp)); return 0;
    case WM_MOUSEMOVE:   onMouseMove(LOWORD(lp), HIWORD(lp));   return 0;
    case WM_LBUTTONUP:   onLButtonUp(LOWORD(lp), HIWORD(lp));   return 0;
    case WM_MOUSEWHEEL:  onMouseWheel(GET_WHEEL_DELTA_WPARAM(wp)); return 0;
    case WM_KEYDOWN:     onKeyDown(wp); return 0;
    case WM_CHAR:        onChar((wchar_t)wp); return 0;
    case WM_DROPFILES:   onDropFiles((HDROP)wp); return 0;
    case WM_DESTROY:     PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(hw, msg, wp, lp);
}

// ── Paint ─────────────────────────────────────────────────────────────────

void MainWindowD2D::onPaint() {
    if (FAILED(createDevRes()) || !m_rt) return;
    m_rt->BeginDraw();
    m_rt->SetTransform(D2D1::Matrix3x2F::Identity());
    m_rt->Clear(D2D1::ColorF(Pal::Bg));
    render();
    HRESULT hr = m_rt->EndDraw();
    if (hr == D2DERR_RECREATE_TARGET) discardDevRes();
}

void MainWindowD2D::render() {
    auto sz = m_rt->GetSize();
    float W = sz.width, H = sz.height;

    renderSidebar(0, 0, SIDEBAR_W, H - PLAYER_H);
    renderContent(SIDEBAR_W, 0, W - SIDEBAR_W, H - PLAYER_H);
    renderPlayerBar(0, H - PLAYER_H, W, PLAYER_H);
}

// ── drawText helper ───────────────────────────────────────────────────────

void MainWindowD2D::drawText(const std::wstring& t, IDWriteTextFormat* fmt,
    float x, float y, float w, float h, ID2D1Brush* br,
    DWRITE_TEXT_ALIGNMENT ha, DWRITE_PARAGRAPH_ALIGNMENT va)
{
    if (!fmt || !br || t.empty() || w <= 0 || h <= 0) return;
    fmt->SetTextAlignment(ha);
    fmt->SetParagraphAlignment(va);
    m_rt->DrawText(t.c_str(), (UINT32)t.size(), fmt,
        D2D1::RectF(x, y, x+w, y+h), br,
        D2D1_DRAW_TEXT_OPTIONS_CLIP);
}

// ── Sidebar ───────────────────────────────────────────────────────────────

void MainWindowD2D::renderSidebar(float x, float y, float w, float h) {
    m_rt->FillRectangle(D2D1::RectF(x,y,x+w,y+h), m_brSurface);
    m_rt->DrawLine({x+w,y},{x+w,y+h}, m_brDivider, 1.0f);

    // Logo
    Icons::draw(m_rt, m_brAccent, Icons::Id::LibraryMusic, x+16, y+16, 22);
    drawText(L"FLAC PLAYER", m_fmtLogo, x+46, y+16, w-60, 24, m_brText);

    // Scan progress bar (shown while scanning)
    float baseY = y + 54;
    auto& lib = LibraryManager::getInstance();
    if (lib.isScanningActive()) {
        renderScanProgress(x+10, baseY, w-20);
        baseY += 32;
    }

    float cy = baseY + 8;
    float iH = 36.0f, iGap = 3.0f;

    auto navItem = [&](Screen scr, Icons::Id ico, const wchar_t* label, int badge = -1) {
        bool active = (m_screen == scr);
        if (active) {
            m_rt->FillRoundedRectangle(
                D2D1::RoundedRect(D2D1::RectF(x+8,cy,x+w-8,cy+iH), 7,7), m_brNavPill);
            m_rt->DrawRoundedRectangle(
                D2D1::RoundedRect(D2D1::RectF(x+8,cy,x+w-8,cy+iH), 7,7), m_brAccent, 1.0f);
        }
        auto* iconBr = active ? m_brAccent : m_brTextDim;
        auto* textBr = active ? m_brAccent : m_brText;
        Icons::draw(m_rt, iconBr, ico, x+16, cy+8, 20);
        float textX = x+44;
        float badgeAreaW = (badge >= 0) ? 36.0f : 0.0f;
        drawText(label, m_fmtNavItem, textX, cy, w-54-badgeAreaW, iH, textBr);
        if (badge >= 0) {
            std::wstring bs = std::to_wstring(badge);
            drawText(bs, m_fmtCaption, x+w-44, cy, 36, iH, m_brTextDim, DWRITE_TEXT_ALIGNMENT_TRAILING);
        }
        cy += iH + iGap;
    };

    auto sectionHdr = [&](const wchar_t* t) {
        cy += 8;
        drawText(t, m_fmtSectionHdr, x+16, cy, w-24, 16, m_brTextDim);
        cy += 22;
    };

    sectionHdr(L"МЕДИАТЕКА");
    navItem(Screen::AllTracks, Icons::Id::LibraryMusic, L"Все треки",  (int)lib.getTrackCount());
    navItem(Screen::Albums,    Icons::Id::Album,         L"Альбомы",   (int)lib.getAlbums().size());
    navItem(Screen::Artists,   Icons::Id::Person,         L"Исполнители",(int)lib.getArtists().size());
    navItem(Screen::Folders,   Icons::Id::FolderOpen,    L"Папки",     (int)lib.getAutoScanFolders().size());

    sectionHdr(L"ПЛЕЙЛИСТЫ");

    // + Новый плейлист
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+8,cy,x+w-8,cy+iH), 7,7), m_brCard);
    m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+8,cy,x+w-8,cy+iH), 7,7), m_brDivider, 1.0f);
    Icons::draw(m_rt, m_brAccent, Icons::Id::Add, x+16, cy+8, 20);
    drawText(L"Новый плейлист", m_fmtNavItem, x+44, cy, w-54, iH, m_brAccent);
    cy += iH + iGap;

    for (const auto& pl : lib.getPlaylists()) {
        bool active = (m_screen == Screen::PlaylistDetail && m_detailPlaylist == pl.id);
        if (active) {
            m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+8,cy,x+w-8,cy+iH),7,7),m_brNavPill);
            m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+8,cy,x+w-8,cy+iH),7,7),m_brAccent,1.0f);
        }
        Icons::draw(m_rt, active?m_brAccent:m_brTextDim, Icons::Id::QueueMusic, x+16, cy+8, 20);
        drawText(W(pl.name), m_fmtNavItem, x+44, cy, w-90, iH, active?m_brAccent:m_brText);
        drawText(std::to_wstring(pl.trackPaths.size()), m_fmtCaption,
                 x+w-44, cy, 36, iH, m_brTextDim, DWRITE_TEXT_ALIGNMENT_TRAILING);
        cy += iH + iGap;
    }

    sectionHdr(L"КОЛЛЕКЦИЯ");
    navItem(Screen::Favorites, Icons::Id::Favorite, L"Любимые",  (int)lib.getFavoriteIndices().size());
    navItem(Screen::Hidden,    Icons::Id::VisibilityOff, L"Скрытые", (int)lib.getHiddenIndices().size());

    // Settings pinned at bottom
    float settY = y + h - iH - 10;
    bool active = (m_screen == Screen::Settings);
    if (active) { m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+8,settY,x+w-8,settY+iH),7,7),m_brNavPill); }
    Icons::draw(m_rt, active?m_brAccent:m_brTextDim, Icons::Id::Settings, x+16, settY+8, 20);
    drawText(L"Настройки", m_fmtNavItem, x+44, settY, w-54, iH, active?m_brAccent:m_brText);
}

void MainWindowD2D::renderScanProgress(float x, float y, float w) {
    auto& lib = LibraryManager::getInstance();
    int total   = lib.getScanTotal();
    int scanned = lib.getScanScanned();

    float pct = (total > 0) ? (float)scanned / total : 0.0f;
    // Track background
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x,y+16,x+w,y+20),2,2), m_brDivider);
    if (pct > 0) m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x,y+16,x+pct*w,y+20),2,2), m_brAccent);

    std::wstring lbl = L"Сканирование " + std::to_wstring(scanned) + L"/" + std::to_wstring(total);
    drawText(lbl, m_fmtBody, x, y, w, 16, m_brTextDim);
}

// ── Content dispatcher ────────────────────────────────────────────────────

void MainWindowD2D::renderContent(float x, float y, float w, float h) {
    switch (m_screen) {
    case Screen::AllTracks:     renderAllTracks(x,y,w,h);     break;
    case Screen::Albums:        renderAlbumsGrid(x,y,w,h);    break;
    case Screen::AlbumDetail:   renderAlbumDetail(x,y,w,h);   break;
    case Screen::Artists:       renderArtistList(x,y,w,h);    break;
    case Screen::ArtistDetail:  renderArtistDetail(x,y,w,h);  break;
    case Screen::Folders:       renderFolders(x,y,w,h);       break;
    case Screen::Favorites:     renderFavorites(x,y,w,h);     break;
    case Screen::Hidden:        renderHidden(x,y,w,h);        break;
    case Screen::PlaylistDetail:renderPlaylistDetail(x,y,w,h);break;
    case Screen::Settings:      renderSettings(x,y,w,h);      break;
    }
}

// ── Track Row ─────────────────────────────────────────────────────────────

void MainWindowD2D::renderTrackRow(float x, float y, float w, float rowH,
                                    size_t realIdx, bool isPlaying, bool isHover) {
    auto tracks = LibraryManager::getInstance().getTracksCopy();
    if (realIdx >= tracks.size()) return;
    const auto& t = tracks[realIdx];
    bool isFav = LibraryManager::getInstance().isFavorite(t.filePath);

    // Card background
    auto* cardBr = isPlaying ? m_brCardActive : (isHover ? m_brCardHover : m_brCard);
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x,y,x+w,y+rowH), CARD_R,CARD_R), cardBr);
    if (isPlaying)
        m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x,y,x+w,y+rowH), CARD_R,CARD_R), m_brAccent, 1.2f);
    else
        m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x,y,x+w,y+rowH), CARD_R,CARD_R), m_brDivider, 0.8f);

    // Album art thumbnail
    float ax = x + 10, ay = y + (rowH - ART_SZ) * 0.5f;
    ID2D1Bitmap* bmp = AlbumArtExtractor::getInstance().getAlbumArt(t.filePath, m_rt);
    if (bmp) {
        m_rt->DrawBitmap(bmp, D2D1::RectF(ax, ay, ax+ART_SZ, ay+ART_SZ));
    } else {
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(ax,ay,ax+ART_SZ,ay+ART_SZ),6,6),
            isPlaying ? m_brAccentBg : m_brDivider);
        Icons::draw(m_rt, isPlaying?m_brAccent:m_brTextDim, Icons::Id::Album,
                    ax+(ART_SZ-22)*0.5f, ay+(ART_SZ-22)*0.5f, 22);
    }
    if (isPlaying)
        Icons::draw(m_rt, m_brAccent, Icons::Id::Play, ax+(ART_SZ-16)*0.5f, ay+(ART_SZ-16)*0.5f, 16);

    // Track info
    float tx = ax + ART_SZ + 12;
    float availW = w - 10 - ART_SZ - 12 - 200; // leave room for badges/actions

    auto* titleBr = isPlaying ? m_brAccent : m_brText;
    drawText(W(t.title), m_fmtTitle, tx, y + 8, availW, 22, titleBr);
    drawText(W(t.artist), m_fmtBody, tx, y + 32, availW, 18, m_brTextDim);

    // Right side: HiRes badge, format, duration, fav, menu
    float rightX = x + w - 10;
    // duration
    drawText(fmtTime(t.durationMs), m_fmtBody, rightX-60, y, 56, rowH, m_brTextDim,
             DWRITE_TEXT_ALIGNMENT_TRAILING);
    // fav
    Icons::draw(m_rt, isFav?m_brRed:m_brTextDim, isFav?Icons::Id::Favorite:Icons::Id::FavoriteOutline,
                rightX-94, y+(rowH-18)*0.5f, 18);
    // 3-dots
    Icons::draw(m_rt, m_brTextDim, Icons::Id::MoreVert, rightX-118, y+(rowH-18)*0.5f, 18);

    // HiRes badge
    if (t.isHiRes) {
        float bx = rightX - 188, by = y + (rowH-20)*0.5f;
        m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(bx,by,bx+54,by+20),4,4), m_brHiRes, 1.2f);
        drawText(L"HI-RES", m_fmtCaption, bx+4, by, 48, 20, m_brHiRes);
    }
    // format info
    std::wstring fmt = W(t.format) + L" " + std::to_wstring(t.sampleRate/1000) + L"k";
    drawText(fmt, m_fmtCaption, rightX-248, y, 56, rowH, m_brTextDim, DWRITE_TEXT_ALIGNMENT_TRAILING);
}

// ── All Tracks ────────────────────────────────────────────────────────────

void MainWindowD2D::renderAllTracks(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();

    // Header row
    drawText(L"Все треки", m_fmtHeader, x+PAD, y+14, 300, 32, m_brText);

    // Rescan button
    float btnX = x+w-PAD-36, btnY = y+16;
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(btnX,btnY,btnX+36,btnY+32),7,7), m_brCard);
    Icons::draw(m_rt, m_brTextDim, Icons::Id::Refresh, btnX+8, btnY+6, 20);

    // Add folder button
    float addX = btnX - 150;
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(addX,btnY,addX+140,btnY+32),7,7), m_brCard);
    m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(addX,btnY,addX+140,btnY+32),7,7), m_brAccent, 1.0f);
    Icons::draw(m_rt, m_brAccent, Icons::Id::FolderOpen, addX+10, btnY+6, 20);
    drawText(L"Добавить папку", m_fmtNavItem, addX+36, btnY, 100, 32, m_brText);

    // Search bar
    float searchY = y + 58;
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD,searchY,x+w-PAD,searchY+36),8,8), m_brCard);
    m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD,searchY,x+w-PAD,searchY+36),8,8),
        m_searchFocused ? m_brAccent : m_brDivider, m_searchFocused ? 1.5f : 1.0f);
    Icons::draw(m_rt, m_searchFocused?m_brAccent:m_brTextDim, Icons::Id::Search, x+PAD+10, searchY+9, 18);
    const std::wstring& sq = m_searchW;
    if (!sq.empty())
        drawText(sq, m_fmtSearch, x+PAD+38, searchY, w-PAD*2-50, 36, m_brText);
    else
        drawText(L"Поиск по трекам, исполнителям, альбомам…", m_fmtSearch,
                 x+PAD+38, searchY, w-PAD*2-50, 36, m_brTextDim);

    // Track list (virtual scroll)
    float listY = searchY + 44;
    float listH = h - listY;
    auto indices = lib.getFilteredIndices(N(m_searchW));

    if (indices.empty()) {
        renderEmptyState(x + w*0.5f, listY + listH*0.4f,
            lib.isScanningActive() ? L"Идёт сканирование…" : L"Медиатека пуста",
            lib.isScanningActive() ? L"Подождите, треки добавляются" : L"Перетащите файлы или нажмите «Добавить папку»");
        return;
    }

    int curIdx = lib.getCurrentIndex();
    float itemH = ROW_H + ROW_GAP;

    for (size_t vi = 0; vi < indices.size(); ++vi) {
        float ry = listY + (float)vi * itemH - (float)m_scrollOffset;
        if (ry + ROW_H < listY) continue;
        if (ry > y + h)         break;

        size_t ri = indices[vi];
        bool isPlaying = ((int)ri == curIdx);
        bool isHover   = ((int)vi == m_hoverRow);
        renderTrackRow(x+PAD, ry, w-PAD*2, ROW_H, ri, isPlaying, isHover);
    }
}

// ── Empty State ───────────────────────────────────────────────────────────

void MainWindowD2D::renderEmptyState(float cx, float cy, const wchar_t* title, const wchar_t* sub) {
    float cw = 420, ch = 190;
    float rx = cx - cw*0.5f, ry = cy - ch*0.5f;
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(rx,ry,rx+cw,ry+ch),14,14), m_brCard);
    Icons::draw(m_rt, m_brAccent, Icons::Id::LibraryMusic, cx-18, ry+28, 36);
    drawText(title, m_fmtHeader, rx, ry+76, cw, 30, m_brText, DWRITE_TEXT_ALIGNMENT_CENTER);
    drawText(sub, m_fmtBody, rx+16, ry+112, cw-32, 40, m_brTextDim, DWRITE_TEXT_ALIGNMENT_CENTER);
}

// ── Albums Grid ───────────────────────────────────────────────────────────

void MainWindowD2D::renderAlbumsGrid(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    const auto& albums = lib.getAlbums();

    drawText(L"Альбомы", m_fmtHeader, x+PAD, y+14, 300, 32, m_brText);

    if (albums.empty()) {
        renderEmptyState(x+w*0.5f, y+h*0.4f, L"Нет альбомов",
            lib.isScanningActive() ? L"Идёт сканирование…" : L"Добавьте музыкальные папки");
        return;
    }

    float listY = y + 56;
    float cW = 170, cH = 220, gap = 16;
    int cols = std::max(1, (int)((w - PAD*2 + gap) / (cW + gap)));

    for (size_t i = 0; i < albums.size(); ++i) {
        int col = (int)(i % cols), row = (int)(i / cols);
        float cx2 = x + PAD + col*(cW+gap);
        float cy2 = listY + row*(cH+gap) - m_scrollOffset;

        if (cy2 + cH < listY) continue;
        if (cy2 > y + h)      break;

        const auto& alb = albums[i];
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(cx2,cy2,cx2+cW,cy2+cH),CARD_R,CARD_R), m_brCard);

        ID2D1Bitmap* bmp = AlbumArtExtractor::getInstance().getAlbumArt(alb.samplePath, m_rt);
        float artH = cW - 0; // square art
        if (bmp) {
            m_rt->DrawBitmap(bmp, D2D1::RectF(cx2, cy2, cx2+cW, cy2+artH));
        } else {
            m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(cx2,cy2,cx2+cW,cy2+artH),CARD_R,CARD_R), m_brDivider);
            Icons::draw(m_rt, m_brAccentDim, Icons::Id::Album, cx2+(cW-48)*0.5f, cy2+(artH-48)*0.5f, 48);
        }

        drawText(W(alb.name),   m_fmtTitle, cx2+8, cy2+artH+6,  cW-16, 20, m_brText);
        drawText(W(alb.artist), m_fmtBody,  cx2+8, cy2+artH+28, cW-16, 18, m_brTextDim);
        std::wstring cnt = std::to_wstring(alb.indices.size()) + L" треков";
        drawText(cnt, m_fmtCaption, cx2+8, cy2+artH+48, cW-16, 16, m_brTextDim);

        Icons::draw(m_rt, m_brTextDim, Icons::Id::ChevronRight, cx2+cW-24, cy2+artH+8, 16);
    }
}

// ── Album Detail ──────────────────────────────────────────────────────────

void MainWindowD2D::renderAlbumDetail(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    const auto& albums = lib.getAlbums();

    // Find album
    const AlbumInfo* alb = nullptr;
    for (const auto& a : albums) if (a.name == m_detailAlbum) { alb = &a; break; }
    if (!alb) { navigateTo(Screen::Albums); return; }

    // Back button
    Icons::draw(m_rt, m_brTextDim, Icons::Id::ChevronLeft, x+PAD, y+18, 22);
    drawText(L"Альбомы", m_fmtNavItem, x+PAD+28, y+14, 160, 30, m_brTextDim);

    // Album header
    float artSide = 130;
    ID2D1Bitmap* bmp = AlbumArtExtractor::getInstance().getAlbumArt(alb->samplePath, m_rt);
    if (bmp) m_rt->DrawBitmap(bmp, D2D1::RectF(x+PAD, y+54, x+PAD+artSide, y+54+artSide));
    else {
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD,y+54,x+PAD+artSide,y+54+artSide),10,10), m_brDivider);
        Icons::draw(m_rt, m_brAccentDim, Icons::Id::Album, x+PAD+42, y+54+42, 46);
    }
    drawText(W(alb->name),   m_fmtHeader, x+PAD+artSide+18, y+60, w-artSide-PAD*3, 30, m_brText);
    drawText(W(alb->artist), m_fmtTitle,  x+PAD+artSide+18, y+96, w-artSide-PAD*3, 22, m_brTextDim);
    std::wstring cnt = std::to_wstring(alb->indices.size()) + L" треков";
    drawText(cnt, m_fmtBody, x+PAD+artSide+18, y+122, w-artSide-PAD*3, 20, m_brTextDim);

    // Tracklist
    int curIdx = lib.getCurrentIndex();
    float listY = y + 200;
    float itemH = ROW_H + ROW_GAP;

    for (size_t vi = 0; vi < alb->indices.size(); ++vi) {
        float ry = listY + (float)vi*itemH - (float)m_scrollOffset;
        if (ry + ROW_H < listY) continue;
        if (ry > y + h) break;
        size_t ri = alb->indices[vi];
        renderTrackRow(x+PAD, ry, w-PAD*2, ROW_H, ri, (int)ri==curIdx, (int)vi==m_hoverRow);
    }
}

// ── Artists ───────────────────────────────────────────────────────────────

void MainWindowD2D::renderArtistList(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    const auto& artists = lib.getArtists();

    drawText(L"Исполнители", m_fmtHeader, x+PAD, y+14, 300, 32, m_brText);

    if (artists.empty()) {
        renderEmptyState(x+w*0.5f, y+h*0.4f, L"Нет исполнителей",
            lib.isScanningActive() ? L"Идёт сканирование…" : L"Добавьте музыкальные папки");
        return;
    }

    float listY = y + 56;
    float rH = 64, gap = 6;

    for (size_t i = 0; i < artists.size(); ++i) {
        float ry = listY + (float)i*(rH+gap) - (float)m_scrollOffset;
        if (ry + rH < listY) continue;
        if (ry > y + h) break;

        const auto& art = artists[i];
        bool isHover = ((int)i == m_hoverRow);

        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD,ry,x+w-PAD,ry+rH),CARD_R,CARD_R),
            isHover ? m_brCardHover : m_brCard);

        // Avatar circle
        float ax = x+PAD+10, ay = ry+rH*0.5f;
        m_rt->FillEllipse({{ax+20, ay}, 20, 20}, m_brAccentBg);
        Icons::draw(m_rt, m_brAccent, Icons::Id::Person, ax+4, ay-16, 32);

        // Info
        drawText(W(art.name), m_fmtTitle, ax+48, ry+8, w-PAD*2-100, 24, m_brText);
        std::wstring sub = std::to_wstring(art.albumCount) + L" альбомов · " +
                           std::to_wstring(art.trackCount) + L" треков";
        drawText(sub, m_fmtBody, ax+48, ry+34, w-PAD*2-100, 20, m_brTextDim);

        Icons::draw(m_rt, m_brTextDim, Icons::Id::ChevronRight, x+w-PAD-26, ry+(rH-20)*0.5f, 20);
    }
}

// ── Artist Detail ─────────────────────────────────────────────────────────

void MainWindowD2D::renderArtistDetail(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    const auto& artists = lib.getArtists();

    const ArtistInfo* art = nullptr;
    for (const auto& a : artists) if (a.name == m_detailArtist) { art = &a; break; }
    if (!art) { navigateTo(Screen::Artists); return; }

    // Back
    Icons::draw(m_rt, m_brTextDim, Icons::Id::ChevronLeft, x+PAD, y+18, 22);
    drawText(L"Исполнители", m_fmtNavItem, x+PAD+28, y+14, 160, 30, m_brTextDim);

    // Header
    float ax = x+PAD+20, ay = y+54+28;
    m_rt->FillEllipse({{ax, ay}, 28, 28}, m_brAccentBg);
    Icons::draw(m_rt, m_brAccent, Icons::Id::Person, ax-20, ay-24, 44);
    drawText(W(art->name), m_fmtHeader, x+PAD+66, y+58, w-PAD*3, 30, m_brText);
    std::wstring sub = std::to_wstring(art->albumCount) + L" альбомов · " +
                       std::to_wstring(art->trackCount) + L" треков";
    drawText(sub, m_fmtBody, x+PAD+66, y+92, w-PAD*3, 20, m_brTextDim);

    // Tracks
    int curIdx = lib.getCurrentIndex();
    float listY = y + 140;
    float itemH = ROW_H + ROW_GAP;

    for (size_t vi = 0; vi < art->indices.size(); ++vi) {
        float ry = listY + (float)vi*itemH - (float)m_scrollOffset;
        if (ry + ROW_H < listY) continue;
        if (ry > y + h) break;
        size_t ri = art->indices[vi];
        renderTrackRow(x+PAD, ry, w-PAD*2, ROW_H, ri, (int)ri==curIdx, (int)vi==m_hoverRow);
    }
}

// ── Folders ───────────────────────────────────────────────────────────────

void MainWindowD2D::renderFolders(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    drawText(L"Папки медиатеки", m_fmtHeader, x+PAD, y+14, 400, 32, m_brText);

    // Add folder button
    float addX = x+w-PAD-160, addY = y+16;
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(addX,addY,addX+155,addY+32),7,7), m_brCard);
    m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(addX,addY,addX+155,addY+32),7,7), m_brAccent, 1.0f);
    Icons::draw(m_rt, m_brAccent, Icons::Id::FolderOpen, addX+10, addY+6, 20);
    drawText(L"Добавить папку", m_fmtNavItem, addX+36, addY, 116, 32, m_brText);

    const auto& folders = lib.getAutoScanFolders();
    if (folders.empty()) {
        renderEmptyState(x+w*0.5f, y+h*0.45f, L"Нет добавленных папок",
            L"Нажмите «Добавить папку» для добавления корневой директории");
        return;
    }

    float cy = y + 68;
    for (size_t i = 0; i < folders.size(); ++i) {
        float rH = 52;
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD,cy,x+w-PAD,cy+rH),CARD_R,CARD_R), m_brCard);
        Icons::draw(m_rt, m_brAccentDim, Icons::Id::FolderOpen, x+PAD+10, cy+(rH-22)*0.5f, 22);
        drawText(W(folders[i]), m_fmtTitle, x+PAD+40, cy, w-PAD*2-100, rH, m_brText);
        // Rescan folder button
        float rbX = x+w-PAD-86;
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(rbX,cy+10,rbX+36,cy+38),6,6), m_brDivider);
        Icons::draw(m_rt, m_brTextDim, Icons::Id::Refresh, rbX+8, cy+14, 18);
        // Remove button
        float rmX = rbX + 42;
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(rmX,cy+10,rmX+36,cy+38),6,6), m_brDivider);
        Icons::draw(m_rt, m_brRed, Icons::Id::Close, rmX+8, cy+14, 18);

        cy += rH + 8;
    }
}

// ── Favorites ─────────────────────────────────────────────────────────────

void MainWindowD2D::renderFavorites(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    drawText(L"Любимые треки", m_fmtHeader, x+PAD, y+14, 400, 32, m_brText);

    auto indices = lib.getFavoriteIndices();
    if (indices.empty()) {
        renderEmptyState(x+w*0.5f, y+h*0.4f, L"Нет любимых треков",
            L"Нажмите ❤ рядом с треком, чтобы добавить в избранное");
        return;
    }

    int curIdx = lib.getCurrentIndex();
    float listY = y + 56;
    float itemH = ROW_H + ROW_GAP;

    for (size_t vi = 0; vi < indices.size(); ++vi) {
        float ry = listY + (float)vi*itemH - (float)m_scrollOffset;
        if (ry + ROW_H < listY) continue;
        if (ry > y + h) break;
        size_t ri = indices[vi];
        renderTrackRow(x+PAD, ry, w-PAD*2, ROW_H, ri, (int)ri==curIdx, (int)vi==m_hoverRow);
    }
}

// ── Hidden ────────────────────────────────────────────────────────────────

void MainWindowD2D::renderHidden(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    drawText(L"Скрытые треки", m_fmtHeader, x+PAD, y+14, 400, 32, m_brText);

    auto indices = lib.getHiddenIndices();
    if (indices.empty()) {
        renderEmptyState(x+w*0.5f, y+h*0.4f, L"Нет скрытых треков", L"");
        return;
    }

    auto tracks = lib.getTracksCopy();
    float cy = y + 56;
    for (size_t i = 0; i < indices.size(); ++i) {
        size_t ri = indices[i];
        if (ri >= tracks.size()) continue;
        const auto& t = tracks[ri];

        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD,cy,x+w-PAD,cy+ROW_H),CARD_R,CARD_R), m_brCard);
        drawText(W(t.title), m_fmtTitle, x+PAD+14, cy+8, w-PAD*2-140, 22, m_brText);
        drawText(W(t.artist), m_fmtBody, x+PAD+14, cy+32, w-PAD*2-140, 18, m_brTextDim);

        // Restore button
        float rbX = x+w-PAD-120;
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(rbX,cy+14,rbX+112,cy+42),6,6), m_brAccentBg);
        m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(rbX,cy+14,rbX+112,cy+42),6,6), m_brAccent, 1.0f);
        drawText(L"Восстановить", m_fmtCaption, rbX+8, cy+14, 100, 28, m_brAccent);

        cy += ROW_H + ROW_GAP;
    }
}

// ── Playlist Detail ───────────────────────────────────────────────────────

void MainWindowD2D::renderPlaylistDetail(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    const Playlist* pl = lib.getPlaylistById(m_detailPlaylist);
    if (!pl) { navigateTo(Screen::AllTracks); return; }

    drawText(W(pl->name), m_fmtHeader, x+PAD, y+14, 400, 32, m_brText);

    // Rename / Delete
    float delX = x+w-PAD-126, delY = y+18;
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(delX,delY,delX+120,delY+28),6,6), m_brCard);
    m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(delX,delY,delX+120,delY+28),6,6), m_brRed, 1.0f);
    drawText(L"Удалить", m_fmtNavItem, delX+8, delY, 108, 28, m_brRed);

    auto tracks = lib.getTracksCopy();
    if (pl->trackPaths.empty()) {
        renderEmptyState(x+w*0.5f, y+h*0.4f, L"Плейлист пуст",
            L"Добавьте треки через ПКМ → «Добавить в плейлист»");
        return;
    }

    int curIdx = lib.getCurrentIndex();
    float listY = y + 60;
    float itemH = ROW_H + ROW_GAP;

    for (size_t vi = 0; vi < pl->trackPaths.size(); ++vi) {
        float ry = listY + (float)vi*itemH - (float)m_scrollOffset;
        if (ry + ROW_H < listY) continue;
        if (ry > y + h) break;

        const std::string& path = pl->trackPaths[vi];
        size_t ri = tracks.size();
        for (size_t ti = 0; ti < tracks.size(); ++ti)
            if (tracks[ti].filePath == path) { ri = ti; break; }
        if (ri < tracks.size())
            renderTrackRow(x+PAD, ry, w-PAD*2, ROW_H, ri, (int)ri==curIdx, (int)vi==m_hoverRow);
    }
}

// ── Settings ─────────────────────────────────────────────────────────────

void MainWindowD2D::renderSettings(float x, float y, float w, float h) {
    auto& lib = LibraryManager::getInstance();
    drawText(L"Настройки", m_fmtHeader, x+PAD, y+14, 300, 32, m_brText);

    float cy = y + 60 - m_scrollOffset;
    auto secCard = [&](float cardH) -> float {
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD,cy,x+w-PAD,cy+cardH),CARD_R,CARD_R), m_brCard);
        float r = cy; cy += cardH + 12; return r;
    };

    // ── Audio Output ──────────────────────────────────────────────────
    auto devices = AudioEngineWin::getInstance().getAudioDevices();
    float dH = 42 + (float)devices.size() * 34;
    float baseY = secCard(dH);
    drawText(L"Устройство вывода звука", m_fmtTitle, x+PAD+14, baseY+12, 400, 22, m_brAccent);
    UINT curDev = AudioEngineWin::getInstance().getCurrentDevice();
    float dy = baseY + 40;
    for (const auto& dev : devices) {
        bool sel = (dev.id == curDev);
        if (sel) {
            m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD+10,dy,x+w-PAD-10,dy+28),5,5), m_brNavPill);
            m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x+PAD+10,dy,x+w-PAD-10,dy+28),5,5), m_brAccent, 1.0f);
        }
        Icons::draw(m_rt, sel?m_brAccent:m_brTextDim, sel?Icons::Id::Check:Icons::Id::VolumeUp, x+PAD+16, dy+4, 20);
        drawText(W(dev.name), m_fmtNavItem, x+PAD+42, dy, w-PAD*2-60, 28, sel?m_brAccent:m_brText);
        dy += 34;
    }

    // ── Min duration ─────────────────────────────────────────────────
    float filterY = secCard(90);
    drawText(L"Фильтр коротких аудиофайлов", m_fmtTitle, x+PAD+14, filterY+12, 400, 22, m_brAccent);
    drawText(L"Игнорировать файлы короче:", m_fmtBody, x+PAD+14, filterY+38, 260, 20, m_brTextDim);

    int opts[] = {0, 10, 30, 60};
    const wchar_t* optLbl[] = {L"Все", L">10с", L">30с", L">60с"};
    float optX = x+PAD+14;
    int curMin = lib.getMinDurationSec();
    for (int k = 0; k < 4; ++k) {
        bool sel = (curMin == opts[k]);
        float bW = 68;
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(optX,filterY+58,optX+bW,filterY+82),6,6),
            sel ? m_brNavPill : m_brDivider);
        if (sel) m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(optX,filterY+58,optX+bW,filterY+82),6,6), m_brAccent, 1.0f);
        drawText(optLbl[k], m_fmtCaption, optX, filterY+58, bW, 24, sel?m_brAccent:m_brText, DWRITE_TEXT_ALIGNMENT_CENTER);
        optX += bW + 8;
    }

    // ── Folders ───────────────────────────────────────────────────────
    const auto& folders = lib.getAutoScanFolders();
    float fH = 50 + std::max(1, (int)folders.size()) * 36.0f;
    float foldY = secCard(fH);
    drawText(L"Автосканирование при запуске", m_fmtTitle, x+PAD+14, foldY+12, 400, 22, m_brAccent);
    float fy = foldY + 38;
    for (const auto& f2 : folders) {
        Icons::draw(m_rt, m_brAccentDim, Icons::Id::FolderOpen, x+PAD+14, fy+4, 18);
        drawText(W(f2), m_fmtBody, x+PAD+38, fy, w-PAD*2-100, 28, m_brText);
        fy += 34;
    }
    if (folders.empty()) drawText(L"Нет добавленных папок", m_fmtBody, x+PAD+14, fy, w-PAD*2, 28, m_brTextDim);
}

// ── Player Bar ────────────────────────────────────────────────────────────

void MainWindowD2D::renderPlayerBar(float x, float y, float w, float h) {
    m_rt->FillRectangle(D2D1::RectF(x,y,x+w,y+h), m_brSurface);
    m_rt->DrawLine({x,y},{x+w,y}, m_brDivider, 1.0f);

    auto& eng = AudioEngineWin::getInstance();
    const TrackInfo& cur = eng.getCurrentTrack();
    int64_t posMs = eng.getPositionMs(), durMs = cur.durationMs;
    PlaybackState state = eng.getState();
    auto& lib = LibraryManager::getInstance();

    // ── Left: Art + Info ─────────────────────────────────────────────
    float ax = x+14, ay = y+14, as_ = h-28;
    if (!cur.filePath.empty()) {
        ID2D1Bitmap* bmp = AlbumArtExtractor::getInstance().getAlbumArt(cur.filePath, m_rt);
        if (bmp) {
            m_rt->DrawBitmap(bmp, D2D1::RectF(ax,ay,ax+as_,ay+as_));
        } else {
            m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(ax,ay,ax+as_,ay+as_),8,8), m_brCard);
            Icons::draw(m_rt, m_brAccentDim, Icons::Id::Album, ax+(as_-32)*0.5f, ay+(as_-32)*0.5f, 32);
        }
    } else {
        m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(ax,ay,ax+as_,ay+as_),8,8), m_brCard);
        Icons::draw(m_rt, m_brDivider, Icons::Id::LibraryMusic, ax+(as_-32)*0.5f, ay+(as_-32)*0.5f, 32);
    }

    float infoX = ax + as_ + 12;
    float infoW = 260;
    if (!cur.filePath.empty()) {
        drawText(W(cur.title), m_fmtTitle, infoX, y+12, infoW, 22, m_brText);
        drawText(W(cur.artist), m_fmtBody, infoX, y+36, infoW, 18, m_brTextDim);
        if (cur.isHiRes) {
            m_rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(infoX,y+60,infoX+50,y+76),3,3), m_brHiRes, 1.0f);
            drawText(L"HI-RES", m_fmtCaption, infoX+4, y+60, 46, 16, m_brHiRes);
        }
    } else {
        drawText(L"Ничего не играет", m_fmtBody, infoX, y, infoW, h, m_brTextDim,
                 DWRITE_TEXT_ALIGNMENT_LEADING, DWRITE_PARAGRAPH_ALIGNMENT_CENTER);
    }

    // ── Center: Controls + Seekbar ────────────────────────────────────
    float cX = w * 0.5f;
    float pbY = y + h * 0.5f - 4;

    // Shuffle
    bool isShuf = lib.isShuffle();
    Icons::draw(m_rt, isShuf?m_brAccent:m_brTextDim, Icons::Id::Shuffle, cX-120, y+16, 22);
    // Prev
    Icons::draw(m_rt, m_brText, Icons::Id::SkipPrev, cX-74, y+14, 24);
    // Play/Pause circle
    m_rt->FillEllipse({{cX, y+26}, 22, 22}, m_brAccent);
    if (state == PlaybackState::PLAYING)
        Icons::draw(m_rt, m_brDark, Icons::Id::Pause, cX-8, y+14, 24);
    else
        Icons::draw(m_rt, m_brDark, Icons::Id::Play, cX-8, y+14, 24);
    // Next
    Icons::draw(m_rt, m_brText, Icons::Id::SkipNext, cX+50, y+14, 24);
    // Repeat
    RepeatMode rm = lib.getRepeatMode();
    auto repIcon = (rm == RepeatMode::ONE) ? Icons::Id::RepeatOne : Icons::Id::Repeat;
    Icons::draw(m_rt, rm!=RepeatMode::OFF?m_brAccent:m_brTextDim, repIcon, cX+98, y+16, 22);

    // Seekbar
    float skW = std::min(520.f, w*0.42f);
    float skX = cX - skW*0.5f, skY = y + 62;
    float prog = (durMs > 0) ? std::max(0.f, std::min((float)posMs/(float)durMs, 1.f)) : 0.f;
    float fillW = prog * skW;

    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(skX,skY,skX+skW,skY+4),2,2), m_brDivider);
    if (fillW > 0) m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(skX,skY,skX+fillW,skY+4),2,2), m_brAccent);
    m_rt->FillEllipse({{skX+fillW, skY+2}, 5.5f, 5.5f}, m_brAccent);

    drawText(fmtTime(posMs), m_fmtBody, skX-52, skY-6, 48, 16, m_brTextDim, DWRITE_TEXT_ALIGNMENT_TRAILING);
    drawText(fmtTime(durMs), m_fmtBody, skX+skW+4, skY-6, 48, 16, m_brTextDim);

    // ── Right: Volume ─────────────────────────────────────────────────
    bool muted = eng.isMuted();
    float vol = muted ? 0.f : eng.getVolume();
    float vW = 96, vX = w - 24 - vW, vY = y + h*0.5f - 2;
    Icons::draw(m_rt, muted?m_brTextDim:m_brAccent,
                muted?Icons::Id::VolumeMute:Icons::Id::VolumeUp, vX-30, vY-10, 22);
    m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(vX,vY,vX+vW,vY+4),2,2), m_brDivider);
    float vFill = vol*vW;
    if (vFill > 0) m_rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(vX,vY,vX+vFill,vY+4),2,2), m_brAccent);
    m_rt->FillEllipse({{vX+vFill, vY+2}, 4.5f, 4.5f}, m_brAccent);
}

// ── Input: Mouse ──────────────────────────────────────────────────────────

void MainWindowD2D::navigateTo(Screen scr) {
    m_screen = scr;
    m_scrollOffset = 0;
    m_hoverRow = -1;
    InvalidateRect(m_hwnd, nullptr, FALSE);
}

void MainWindowD2D::onLButtonDown(int mx, int my) {
    auto sz = m_rt->GetSize();
    float W = sz.width, H = sz.height;
    auto& lib = LibraryManager::getInstance();
    auto& eng = AudioEngineWin::getInstance();

    // Declare layout vars up front so goto-repaint doesn't bypass initialization
    float contX = SIDEBAR_W, contW = W - SIDEBAR_W;

    // ── Player bar ───────────────────────────────────────────────────
    if (my >= H - PLAYER_H) {
        float y = H - PLAYER_H;
        float cX = W * 0.5f;

        // Play/Pause circle
        if (hypotf(mx - cX, my - (y+26)) < 25) { eng.togglePlayPause(); goto repaint; }
        // Skip Prev
        if (mx >= cX-82 && mx <= cX-52 && my >= y+10 && my <= y+42) { lib.playPrevious(); goto repaint; }
        // Skip Next
        if (mx >= cX+42 && mx <= cX+80 && my >= y+10 && my <= y+42) { lib.playNext(); goto repaint; }
        // Shuffle
        if (mx >= cX-132 && mx <= cX-108 && my >= y+8) { lib.toggleShuffle(); goto repaint; }
        // Repeat
        if (mx >= cX+90 && mx <= cX+120 && my >= y+8) { lib.toggleRepeat(); goto repaint; }

        // Seekbar
        float skW = std::min(520.f, W*0.42f);
        float skX = cX - skW*0.5f, skY = y+62;
        if (mx >= skX-8 && mx <= skX+skW+8 && my >= skY-10 && my <= skY+14) {
            m_seekDragging = true;
            float p = std::max(0.f, std::min((mx-skX)/skW, 1.f));
            eng.seekTo((int64_t)(p * eng.getDurationMs()));
            goto repaint;
        }
        // Volume mute icon click
        float vW = 96; float vX = W - 24 - vW;
        if (mx >= vX-34 && mx <= vX-4 && my >= y+30 && my <= y+64) { eng.toggleMute(); goto repaint; }
        // Volume slider
        float vY = y + PLAYER_H*0.5f - 2;
        if (mx >= vX && mx <= vX+vW && my >= vY-10 && my <= vY+14) {
            m_volDragging = true;
            float v = std::max(0.f, std::min((mx-vX)/(float)vW, 1.f));
            eng.setVolume(v); goto repaint;
        }
        return;
    }

    // ── Sidebar ──────────────────────────────────────────────────────
    if (mx < SIDEBAR_W) {
        float cy = lib.isScanningActive() ? 54+32+8 : 54+8;
        float iH = 36, iGap = 3;

        auto checkNav = [&](Screen scr) {
            cy += 8 + 22; // section hdr
            bool hit = my >= cy && my <= cy + iH;
            cy += iH + iGap;
            if (hit) { navigateTo(scr); }
            return hit;
        };

        // МЕДИАТЕКА
        cy += 8 + 22;
        auto tryNav = [&](Screen scr) -> bool {
            bool hit = (my >= cy && my <= cy + iH);
            cy += iH + iGap;
            if (hit) navigateTo(scr);
            return hit;
        };
        if (tryNav(Screen::AllTracks)) return;
        if (tryNav(Screen::Albums))    return;
        if (tryNav(Screen::Artists))   return;
        if (tryNav(Screen::Folders))   return;

        // ПЛЕЙЛИСТЫ
        cy += 8 + 22;
        // New playlist button
        if (my >= cy && my <= cy + iH) { promptCreatePlaylist(); return; }
        cy += iH + iGap;

        for (const auto& pl : lib.getPlaylists()) {
            if (my >= cy && my <= cy + iH) {
                m_detailPlaylist = pl.id;
                navigateTo(Screen::PlaylistDetail);
                return;
            }
            cy += iH + iGap;
        }

        // КОЛЛЕКЦИЯ
        cy += 8 + 22;
        if (tryNav(Screen::Favorites)) return;
        if (tryNav(Screen::Hidden))    return;

        // Settings (pinned bottom)
        float settY = (H - PLAYER_H) - iH - 10;
        if (my >= settY && my <= settY + iH) { navigateTo(Screen::Settings); return; }
        return;
    }

    // ── Content area ─────────────────────────────────────────────────

    if (m_screen == Screen::AllTracks) {
        // Rescan button
        float btnX = contX+contW-PAD-36;
        if (mx >= btnX && mx <= btnX+36 && my >= 16 && my <= 48) {
            lib.rescan(); goto repaint;
        }
        // Add folder button
        float addX = btnX - 150;
        if (mx >= addX && mx <= addX+140 && my >= 16 && my <= 48) {
            addFolderDialog(); goto repaint;
        }
        // Search bar
        if (mx >= contX+PAD && mx <= W-PAD && my >= 58 && my <= 94) {
            m_searchFocused = true; goto repaint;
        } else if (m_searchFocused && my > 94) {
            m_searchFocused = false;
        }
        // Track rows
        float listY = 58+36+8;
        if (my > listY && my < H - PLAYER_H) {
            float itemH = ROW_H + ROW_GAP;
            int vi = (int)((my - listY + m_scrollOffset) / itemH);
            auto indices = lib.getFilteredIndices(N(m_searchW));
            if (vi >= 0 && vi < (int)indices.size()) {
                size_t ri = indices[vi];
                auto tracks = lib.getTracksCopy();
                if (ri < tracks.size()) {
                    // Fav icon click
                    if (mx >= W-PAD-100 && mx <= W-PAD-76) {
                        lib.toggleFavorite(tracks[ri].filePath); goto repaint;
                    }
                    // Otherwise play
                    lib.playIndex(ri); goto repaint;
                }
            }
        }
    }
    else if (m_screen == Screen::Albums) {
        float listY = SIDEBAR_W > 0 ? 56.f : 56.f;
        float cW2 = 170, cH = 220, gap = 16;
        int cols = std::max(1, (int)((contW - PAD*2 + gap) / (cW2 + gap)));
        const auto& albums = lib.getAlbums();
        for (size_t i = 0; i < albums.size(); ++i) {
            int col = (int)(i%cols), row = (int)(i/cols);
            float cx2 = contX+PAD+col*(cW2+gap), cy2 = listY+row*(cH+gap) - m_scrollOffset;
            if (mx >= cx2 && mx <= cx2+cW2 && my >= cy2 && my <= cy2+cH) {
                m_detailAlbum = albums[i].name;
                navigateTo(Screen::AlbumDetail);
                return;
            }
        }
    }
    else if (m_screen == Screen::Artists) {
        float listY = 56.f;
        float rH = 64, gap = 6;
        const auto& artists = lib.getArtists();
        for (size_t i = 0; i < artists.size(); ++i) {
            float ry = listY + (float)i*(rH+gap) - m_scrollOffset;
            if (my >= ry && my <= ry+rH && mx >= contX+PAD && mx <= W-PAD) {
                m_detailArtist = artists[i].name;
                navigateTo(Screen::ArtistDetail);
                return;
            }
        }
    }
    else if (m_screen == Screen::AlbumDetail || m_screen == Screen::ArtistDetail) {
        // Back button
        if (mx >= contX+PAD && mx <= contX+PAD+160 && my >= 14 && my <= 44) {
            navigateTo(m_screen == Screen::AlbumDetail ? Screen::Albums : Screen::Artists);
            return;
        }
        // Tracks
        float listY = (m_screen == Screen::AlbumDetail) ? 200.f : 140.f;
        float itemH = ROW_H + ROW_GAP;
        if (my > listY) {
            int vi = (int)((my - listY + m_scrollOffset) / itemH);
            const auto* indices = m_screen == Screen::AlbumDetail
                ? &lib.getAlbums()[0].indices  // placeholder; proper below
                : nullptr;

            // Get correct index list
            std::vector<size_t> idxList;
            if (m_screen == Screen::AlbumDetail) {
                for (const auto& a : lib.getAlbums()) if (a.name == m_detailAlbum) { idxList = a.indices; break; }
            } else {
                for (const auto& a : lib.getArtists()) if (a.name == m_detailArtist) { idxList = a.indices; break; }
            }
            if (vi >= 0 && vi < (int)idxList.size()) {
                lib.playIndices(idxList, vi); goto repaint;
            }
        }
    }
    else if (m_screen == Screen::Folders) {
        float addX = contX+contW-PAD-160, addY = 16.f;
        if (mx >= addX && mx <= addX+155 && my >= addY && my <= addY+32) {
            addFolderDialog(); goto repaint;
        }
        float cy = 68.f;
        const auto& folders = lib.getAutoScanFolders();
        for (size_t i = 0; i < folders.size(); ++i) {
            float rH = 52;
            float rbX = contX+contW-PAD-86;
            float rmX = rbX+42;
            if (my >= cy+10 && my <= cy+38) {
                if (mx >= (int)rbX && mx <= (int)rbX+36) {
                    lib.scanDirectoryAsync(folders[i]); goto repaint;
                }
                if (mx >= (int)rmX && mx <= (int)rmX+36) {
                    lib.removeAutoScanFolder(folders[i]); goto repaint;
                }
            }
            cy += rH+8;
        }
    }
    else if (m_screen == Screen::Favorites) {
        float listY = 56.f; float itemH = ROW_H + ROW_GAP;
        auto indices = lib.getFavoriteIndices();
        int vi = (int)((my - listY + m_scrollOffset) / itemH);
        if (vi >= 0 && vi < (int)indices.size()) { lib.playIndex(indices[vi]); goto repaint; }
    }
    else if (m_screen == Screen::Hidden) {
        auto tracks = lib.getTracksCopy();
        auto indices = lib.getHiddenIndices();
        float cy = 56.f; float itemH = ROW_H + ROW_GAP;
        int vi = (int)((my - cy + m_scrollOffset) / itemH);
        if (vi >= 0 && vi < (int)indices.size()) {
            // Restore button
            float rbX = contX+contW-PAD-120;
            if (mx >= rbX && mx <= rbX+112) {
                lib.toggleHidden(tracks[indices[vi]].filePath); goto repaint;
            }
        }
    }
    else if (m_screen == Screen::PlaylistDetail) {
        // Delete button
        float delX = contX+contW-PAD-126, delY = 18.f;
        if (mx >= delX && mx <= delX+120 && my >= delY && my <= delY+28) {
            lib.deletePlaylist(m_detailPlaylist);
            navigateTo(Screen::AllTracks); return;
        }
        // Tracks
        float listY = 60.f; float itemH = ROW_H + ROW_GAP;
        const Playlist* pl = lib.getPlaylistById(m_detailPlaylist);
        if (pl) {
            int vi = (int)((my - listY + m_scrollOffset) / itemH);
            auto tracks = lib.getTracksCopy();
            if (vi >= 0 && vi < (int)pl->trackPaths.size()) {
                for (size_t ti = 0; ti < tracks.size(); ++ti)
                    if (tracks[ti].filePath == pl->trackPaths[vi]) { lib.playIndex(ti); break; }
                goto repaint;
            }
        }
    }
    else if (m_screen == Screen::Settings) {
        float cy = 60 - m_scrollOffset;
        // Audio device selection
        auto devices = AudioEngineWin::getInstance().getAudioDevices();
        float dH = 42 + (float)devices.size()*34;
        float basY = cy;
        float dy = basY + 40;
        for (const auto& dev : devices) {
            if (mx >= contX+PAD+10 && mx <= W-PAD-10 && my >= dy && my <= dy+28) {
                AudioEngineWin::getInstance().setOutputDevice(dev.id); goto repaint;
            }
            dy += 34;
        }
        cy += dH + 12;
        // Min duration filter chips
        float filterY = cy;
        cy += 90 + 12;
        int opts[] = {0, 10, 30, 60};
        float optX = contX+PAD+14;
        for (int k = 0; k < 4; ++k) {
            if (mx >= optX && mx <= optX+68 && my >= filterY+58 && my <= filterY+82) {
                lib.setMinDurationSec(opts[k]); goto repaint;
            }
            optX += 76;
        }
    }

    return;
repaint:
    InvalidateRect(m_hwnd, nullptr, FALSE);
}

void MainWindowD2D::onRButtonDown(int mx, int my) {
    auto sz = m_rt->GetSize(); float W = sz.width, H = sz.height;
    if (my >= H - PLAYER_H || mx < SIDEBAR_W) return;

    float listY = 58+36+8;
    if (m_screen == Screen::AllTracks && my > listY) {
        auto& lib = LibraryManager::getInstance();
        float itemH = ROW_H + ROW_GAP;
        int vi = (int)((my - listY + m_scrollOffset) / itemH);
        auto indices = lib.getFilteredIndices(N(m_searchW));
        if (vi >= 0 && vi < (int)indices.size()) {
            showContextMenu(mx, my, indices[vi]);
        }
    }
}

void MainWindowD2D::onMouseMove(int mx, int my) {
    auto sz = m_rt->GetSize(); float W = sz.width, H = sz.height;

    if (m_seekDragging) {
        float cX = W*0.5f, skW = std::min(520.f,W*0.42f), skX = cX-skW*0.5f;
        float p = std::max(0.f, std::min((mx-skX)/skW, 1.f));
        AudioEngineWin::getInstance().seekTo((int64_t)(p*AudioEngineWin::getInstance().getDurationMs()));
        InvalidateRect(m_hwnd,nullptr,FALSE); return;
    }
    if (m_volDragging) {
        float vW = 96, vX = W-24-vW;
        float v = std::max(0.f, std::min((mx-vX)/(float)vW, 1.f));
        AudioEngineWin::getInstance().setVolume(v);
        InvalidateRect(m_hwnd,nullptr,FALSE); return;
    }

    // Update hover row for track lists
    float listY = (m_screen == Screen::AllTracks) ? 58+36+8 :
                  (m_screen == Screen::Favorites || m_screen == Screen::PlaylistDetail) ? 56 :
                  (m_screen == Screen::Artists) ? 56 :
                  (m_screen == Screen::AlbumDetail) ? 200 :
                  (m_screen == Screen::ArtistDetail) ? 140 : -1;

    if (listY > 0 && my > listY && mx >= SIDEBAR_W) {
        float itemH = ROW_H + ROW_GAP;
        int newHover = (int)((my - listY + m_scrollOffset) / itemH);
        if (newHover != m_hoverRow) { m_hoverRow = newHover; InvalidateRect(m_hwnd,nullptr,FALSE); }
    } else if (m_hoverRow != -1) { m_hoverRow = -1; InvalidateRect(m_hwnd,nullptr,FALSE); }
}

void MainWindowD2D::onLButtonUp(int,int) {
    m_seekDragging = false;
    m_volDragging  = false;
}

void MainWindowD2D::onMouseWheel(int delta) {
    m_scrollOffset -= delta / 2;
    if (m_scrollOffset < 0) m_scrollOffset = 0;
    InvalidateRect(m_hwnd, nullptr, FALSE);
}

// ── Input: Keyboard ───────────────────────────────────────────────────────

void MainWindowD2D::onKeyDown(WPARAM key) {
    auto& eng = AudioEngineWin::getInstance();
    auto& lib = LibraryManager::getInstance();
    bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;

    switch (key) {
    case VK_SPACE: if (!m_searchFocused) { eng.togglePlayPause(); break; } return;
    case VK_LEFT:  if (!m_searchFocused) { ctrl ? lib.playPrevious() : eng.seekTo(eng.getPositionMs()-5000); break; } return;
    case VK_RIGHT: if (!m_searchFocused) { ctrl ? lib.playNext()     : eng.seekTo(eng.getPositionMs()+5000); break; } return;
    case VK_UP:    if (!m_searchFocused) { eng.setVolume(eng.getVolume()+0.05f); break; } return;
    case VK_DOWN:  if (!m_searchFocused) { eng.setVolume(eng.getVolume()-0.05f); break; } return;
    case 'M':      if (!m_searchFocused) { eng.toggleMute(); break; } return;
    case VK_ESCAPE: m_searchFocused = false; m_searchW.clear(); lib.setSearchQuery(""); break;
    default: return;
    }
    InvalidateRect(m_hwnd, nullptr, FALSE);
}

void MainWindowD2D::onChar(wchar_t ch) {
    if (!m_searchFocused) return;
    if (ch == VK_BACK) { if (!m_searchW.empty()) m_searchW.pop_back(); }
    else if (ch >= 32)  m_searchW.push_back(ch);
    LibraryManager::getInstance().setSearchQuery(N(m_searchW));
    m_scrollOffset = 0;
    InvalidateRect(m_hwnd, nullptr, FALSE);
}

// ── Drag and Drop ─────────────────────────────────────────────────────────

void MainWindowD2D::onDropFiles(HDROP hDrop) {
    auto& lib = LibraryManager::getInstance();
    auto& eng = AudioEngineWin::getInstance();
    bool autoplay = (eng.getState() == PlaybackState::STOPPED);
    std::string firstPath;

    UINT cnt = DragQueryFileW(hDrop, 0xFFFFFFFF, nullptr, 0);
    for (UINT i = 0; i < cnt; ++i) {
        wchar_t buf[MAX_PATH]{};
        DragQueryFileW(hDrop, i, buf, MAX_PATH);
        char u8[MAX_PATH]{};
        WideCharToMultiByte(CP_UTF8, 0, buf, -1, u8, MAX_PATH, nullptr, nullptr);

        DWORD attrs = GetFileAttributesW(buf);
        if (attrs != INVALID_FILE_ATTRIBUTES && (attrs & FILE_ATTRIBUTE_DIRECTORY)) {
            lib.scanDirectoryAsync(u8);
        } else if (lib.addTrack(u8) && firstPath.empty()) {
            firstPath = u8;
        }
    }
    DragFinish(hDrop);

    if (autoplay) {
        if (!firstPath.empty()) eng.loadAndPlay(firstPath);
        else if (lib.getTrackCount() > 0) lib.playIndex(0);
    }
    navigateTo(Screen::AllTracks);
}

// ── Context Menu ──────────────────────────────────────────────────────────

void MainWindowD2D::showContextMenu(int mx, int my, size_t trackIdx) {
    auto& lib = LibraryManager::getInstance();
    auto tracks = lib.getTracksCopy();
    if (trackIdx >= tracks.size()) return;
    const std::string& path = tracks[trackIdx].filePath;
    bool isFav = lib.isFavorite(path);

    HMENU hm = CreatePopupMenu();
    HMENU hSub = CreatePopupMenu();

    AppendMenuW(hm, MF_STRING, 101, isFav ? L"Убрать из избранного" : L"Добавить в избранное");
    const auto& pls = lib.getPlaylists();
    if (pls.empty()) AppendMenuW(hSub, MF_GRAYED|MF_STRING, 0, L"(нет плейлистов)");
    for (size_t i = 0; i < pls.size(); ++i) AppendMenuW(hSub, MF_STRING, (UINT)(200+i), W(pls[i].name).c_str());
    AppendMenuW(hSub, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(hSub, MF_STRING, 104, L"Создать новый плейлист…");
    AppendMenuW(hm, MF_POPUP, (UINT_PTR)hSub, L"Добавить в плейлист");
    AppendMenuW(hm, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(hm, MF_STRING, 102, L"Скрыть трек");
    AppendMenuW(hm, MF_STRING, 103, L"Показать в проводнике");

    POINT pt{mx, my};
    ClientToScreen(m_hwnd, &pt);
    int cmd = TrackPopupMenu(hm, TPM_RIGHTBUTTON|TPM_RETURNCMD, pt.x, pt.y, 0, m_hwnd, nullptr);

    if      (cmd == 101) lib.toggleFavorite(path);
    else if (cmd == 102) lib.toggleHidden(path);
    else if (cmd == 103) { std::wstring wp = W(path); ShellExecuteW(nullptr,L"open",L"explorer.exe",(L"/select,\""+wp+L"\"").c_str(),nullptr,SW_SHOW); }
    else if (cmd == 104) promptCreatePlaylist();
    else if (cmd >= 200 && cmd < 200+(int)pls.size()) lib.addTrackToPlaylist(pls[cmd-200].id, path);

    DestroyMenu(hSub); DestroyMenu(hm);
    InvalidateRect(m_hwnd, nullptr, FALSE);
}

// ── Add Folder Dialog ─────────────────────────────────────────────────────

void MainWindowD2D::addFolderDialog() {
    BROWSEINFOW bi{};
    bi.hwndOwner = m_hwnd;
    bi.lpszTitle = L"Выберите папку с музыкой (FLAC / MP3 / WAV)";
    bi.ulFlags   = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
    LPITEMIDLIST pidl = SHBrowseForFolderW(&bi);
    if (!pidl) return;

    wchar_t wp[MAX_PATH]{};
    if (SHGetPathFromIDListW(pidl, wp)) {
        char u8[MAX_PATH]{};
        WideCharToMultiByte(CP_UTF8, 0, wp, -1, u8, MAX_PATH, nullptr, nullptr);
        LibraryManager::getInstance().scanDirectoryAsync(u8);
        navigateTo(Screen::AllTracks);
    }
    CoTaskMemFree(pidl);
}

// ── Playlist prompts ──────────────────────────────────────────────────────

void MainWindowD2D::promptCreatePlaylist() {
    auto& lib = LibraryManager::getInstance();
    std::string id = lib.createPlaylist("Плейлист " + std::to_string(lib.getPlaylists().size()));
    m_detailPlaylist = id;
    navigateTo(Screen::PlaylistDetail);
}

void MainWindowD2D::promptRenamePlaylist(const std::string& id) {
    const Playlist* pl = LibraryManager::getInstance().getPlaylistById(id);
    if (pl) LibraryManager::getInstance().renamePlaylist(id, pl->name + " (2)");
    InvalidateRect(m_hwnd, nullptr, FALSE);
}
