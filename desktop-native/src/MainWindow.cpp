#include "MainWindow.h"
#include <shlobj.h>
#include <sstream>
#include <iomanip>
#include <cmath>

#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "winmm.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")

using namespace Gdiplus;

static std::wstring toWide(const std::string& str) {
    if (str.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), -1, nullptr, 0);
    if (len <= 0) {
        len = MultiByteToWideChar(CP_ACP, 0, str.c_str(), -1, nullptr, 0);
        if (len <= 0) return L"";
        std::wstring wstr(len, 0);
        MultiByteToWideChar(CP_ACP, 0, str.c_str(), -1, &wstr[0], len);
        return wstr;
    }
    std::wstring wstr(len, 0);
    MultiByteToWideChar(CP_UTF8, 0, str.c_str(), -1, &wstr[0], len);
    return wstr;
}

static std::string formatTime(int64_t ms) {
    int totalSec = static_cast<int>(ms / 1000);
    int m = totalSec / 60;
    int s = totalSec % 60;
    std::ostringstream oss;
    oss << m << ":" << std::setw(2) << std::setfill('0') << s;
    return oss.str();
}

MainWindow& MainWindow::getInstance() {
    static MainWindow instance;
    return instance;
}

MainWindow::MainWindow() {
    GdiplusStartupInput gdiplusStartupInput;
    GdiplusStartup(&m_gdiplusToken, &gdiplusStartupInput, nullptr);
}

MainWindow::~MainWindow() {
    if (m_gdiplusToken) {
        GdiplusShutdown(m_gdiplusToken);
    }
}

bool MainWindow::create(HINSTANCE hInstance, int width, int height) {
    m_hInstance = hInstance;

    WNDCLASSEXW wc = {0};
    wc.cbSize = sizeof(WNDCLASSEXW);
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    wc.lpszClassName = L"FlacPlayerNativeWindow";
    RegisterClassExW(&wc);

    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);
    int posX = (screenW - width) / 2;
    int posY = (screenH - height) / 2;

    m_hWnd = CreateWindowExW(
        WS_EX_ACCEPTFILES,
        L"FlacPlayerNativeWindow",
        L"〰️ FlacPlayer Hi-Res (Native C++)",
        WS_OVERLAPPEDWINDOW,
        posX, posY, width, height,
        nullptr, nullptr, hInstance, this
    );

    if (!m_hWnd) return false;

    DragAcceptFiles(m_hWnd, TRUE);
    ShowWindow(m_hWnd, SW_SHOW);
    UpdateWindow(m_hWnd);

    // 60 FPS refresh timer for smooth playback & animations
    SetTimer(m_hWnd, 1, 16, nullptr);

    return true;
}

LRESULT CALLBACK MainWindow::WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    MainWindow* window = nullptr;
    if (msg == WM_CREATE) {
        CREATESTRUCT* cs = reinterpret_cast<CREATESTRUCT*>(lParam);
        window = reinterpret_cast<MainWindow*>(cs->lpCreateParams);
        SetWindowLongPtr(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(window));
    } else {
        window = reinterpret_cast<MainWindow*>(GetWindowLongPtr(hwnd, GWLP_USERDATA));
    }

    if (window) {
        return window->handleMessage(hwnd, msg, wParam, lParam);
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

LRESULT MainWindow::handleMessage(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hwnd, &ps);
            onPaint(hdc);
            EndPaint(hwnd, &ps);
            return 0;
        }
        case WM_ERASEBKGND:
            return 1; // Prevent flickering

        case WM_TIMER:
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;

        case WM_LBUTTONDOWN:
            onMouseDown(LOWORD(lParam), HIWORD(lParam), false);
            return 0;

        case WM_RBUTTONDOWN:
            onMouseDown(LOWORD(lParam), HIWORD(lParam), true);
            return 0;

        case WM_MOUSEMOVE:
            onMouseMove(LOWORD(lParam), HIWORD(lParam));
            return 0;

        case WM_LBUTTONUP:
            onMouseUp(LOWORD(lParam), HIWORD(lParam));
            return 0;

        case WM_MOUSEWHEEL:
            onMouseWheel(GET_WHEEL_DELTA_WPARAM(wParam));
            return 0;

        case WM_DROPFILES:
            onDropFiles((HDROP)wParam);
            return 0;

        case WM_KEYDOWN: {
            switch (wParam) {
                case VK_SPACE:
                    AudioEngineWin::getInstance().togglePlayPause();
                    break;
                case VK_LEFT:
                    if (GetKeyState(VK_CONTROL) & 0x8000) {
                        LibraryManager::getInstance().playPrevious();
                    } else {
                        int64_t pos = AudioEngineWin::getInstance().getPositionMs() - 5000;
                        AudioEngineWin::getInstance().seekTo(pos);
                    }
                    break;
                case VK_RIGHT:
                    if (GetKeyState(VK_CONTROL) & 0x8000) {
                        LibraryManager::getInstance().playNext();
                    } else {
                        int64_t pos = AudioEngineWin::getInstance().getPositionMs() + 5000;
                        AudioEngineWin::getInstance().seekTo(pos);
                    }
                    break;
                case VK_UP: {
                    float v = AudioEngineWin::getInstance().getVolume() + 0.05f;
                    AudioEngineWin::getInstance().setVolume(v);
                    break;
                }
                case VK_DOWN: {
                    float v = AudioEngineWin::getInstance().getVolume() - 0.05f;
                    AudioEngineWin::getInstance().setVolume(v);
                    break;
                }
                case 'M':
                    AudioEngineWin::getInstance().toggleMute();
                    break;
            }
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }

        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

void MainWindow::onPaint(HDC hdc) {
    RECT rc;
    GetClientRect(m_hWnd, &rc);
    int width = rc.right - rc.left;
    int height = rc.bottom - rc.top;

    if (width <= 0 || height <= 0) return;

    // Double buffering
    HDC memDC = CreateCompatibleDC(hdc);
    HBITMAP memBitmap = CreateCompatibleBitmap(hdc, width, height);
    HBITMAP oldBitmap = (HBITMAP)SelectObject(memDC, memBitmap);

    Graphics g(memDC);
    g.SetSmoothingMode(SmoothingModeAntiAlias);
    g.SetTextRenderingHint(TextRenderingHintClearTypeGridFit);

    render(g, width, height);

    BitBlt(hdc, 0, 0, width, height, memDC, 0, 0, SRCCOPY);

    SelectObject(memDC, oldBitmap);
    DeleteObject(memBitmap);
    DeleteDC(memDC);
}

void MainWindow::render(Graphics& g, int width, int height) {
    // Check if current track lyrics need reload
    const TrackInfo& cur = AudioEngineWin::getInstance().getCurrentTrack();
    if (!cur.filePath.empty() && cur.filePath != m_lastTrackPath) {
        m_lastTrackPath = cur.filePath;
        m_currentLyrics = LyricsEngine::findLyricsForTrack(cur.filePath, LibraryManager::getInstance().getCustomLyricsDir());
    }

    // 1. Background
    SolidBrush bgBrush(m_colorBg);
    g.FillRectangle(&bgBrush, 0, 0, width, height);

    int sidebarWidth = 200;
    int bottomBarHeight = 90;
    int contentWidth = width - sidebarWidth;
    int contentHeight = height - bottomBarHeight;

    // 2. Sidebar
    renderSidebar(g, 0, 0, sidebarWidth, height - bottomBarHeight);

    // 3. Content View
    switch (m_currentTab) {
        case ViewTab::LIBRARY:
            renderLibraryView(g, sidebarWidth, 0, contentWidth, contentHeight);
            break;
        case ViewTab::LYRICS:
            renderLyricsView(g, sidebarWidth, 0, contentWidth, contentHeight);
            break;
        case ViewTab::EQUALIZER:
            renderEqualizerView(g, sidebarWidth, 0, contentWidth, contentHeight);
            break;
    }

    // 4. Bottom Player Bar
    renderBottomPlayerBar(g, 0, height - bottomBarHeight, width, bottomBarHeight);
}

void MainWindow::renderSidebar(Graphics& g, int x, int y, int w, int h) {
    SolidBrush surfaceBrush(m_colorSurface);
    g.FillRectangle(&surfaceBrush, x, y, w, h);

    Pen borderPen(m_colorSurfaceVar, 1.0f);
    g.DrawLine(&borderPen, x + w - 1, y, x + w - 1, y + h);

    Font fontTitle(L"Segoe UI", 13, FontStyleBold, UnitPixel);
    Font fontTab(L"Segoe UI", 11, FontStyleRegular, UnitPixel);
    SolidBrush textBrush(m_colorText);
    SolidBrush dimBrush(m_colorTextDim);
    SolidBrush accentBrush(m_colorAccent);

    // App Branding
    g.DrawString(L"〰️ FLAC PLAYER", -1, &fontTitle, PointF(x + 18.0f, y + 20.0f), &accentBrush);
    Font fontSub(L"Segoe UI", 9, FontStyleRegular, UnitPixel);
    g.DrawString(L"Hi-Res Audiophile", -1, &fontSub, PointF(x + 20.0f, y + 38.0f), &dimBrush);

    // Tabs
    const wchar_t* tabs[] = { L"🎵  Медиатека", L"🎤  Тексты песен", L"🎚️  Эквалайзер" };
    for (int i = 0; i < 3; ++i) {
        int tabY = y + 80 + i * 44;
        bool isSelected = (static_cast<int>(m_currentTab) == i);

        if (isSelected) {
            SolidBrush activeTabBg(Color(40, 0, 245, 212));
            g.FillRectangle(&activeTabBg, x + 10, tabY, w - 20, 36);

            Pen activeBorder(m_colorAccent, 1.5f);
            g.DrawRectangle(&activeBorder, x + 10, tabY, w - 20, 36);

            g.DrawString(tabs[i], -1, &fontTab, PointF(x + 24.0f, tabY + 9.0f), &accentBrush);
        } else {
            g.DrawString(tabs[i], -1, &fontTab, PointF(x + 24.0f, tabY + 9.0f), &textBrush);
        }
    }

    // Action Buttons
    int btnY = y + h - 90;
    SolidBrush btnBg(m_colorSurfaceVar);
    g.FillRectangle(&btnBg, x + 12, btnY, w - 24, 32);
    g.DrawRectangle(&borderPen, x + 12, btnY, w - 24, 32);
    Font fontBtn(L"Segoe UI", 9, FontStyleBold, UnitPixel);
    g.DrawString(L"➕ Добавить папку", -1, &fontBtn, PointF(x + 24.0f, btnY + 8.0f), &textBrush);

    int scanY = btnY + 40;
    g.FillRectangle(&btnBg, x + 12, scanY, w - 24, 32);
    g.DrawRectangle(&borderPen, x + 12, scanY, w - 24, 32);
    g.DrawString(L"📂 Папка текстов", -1, &fontBtn, PointF(x + 24.0f, scanY + 8.0f), &dimBrush);
}

void MainWindow::renderLibraryView(Graphics& g, int x, int y, int w, int h) {
    const auto& tracks = LibraryManager::getInstance().getTracks();
    int currentPlayIdx = LibraryManager::getInstance().getCurrentIndex();

    Font fontHeader(L"Segoe UI", 16, FontStyleBold, UnitPixel);
    Font fontTrack(L"Segoe UI", 11, FontStyleBold, UnitPixel);
    Font fontArtist(L"Segoe UI", 10, FontStyleRegular, UnitPixel);
    Font fontBadge(L"Segoe UI", 8, FontStyleBold, UnitPixel);

    SolidBrush textBrush(m_colorText);
    SolidBrush dimBrush(m_colorTextDim);
    SolidBrush accentBrush(m_colorAccent);
    SolidBrush orangeBrush(m_colorOrange);
    Pen dividerPen(m_colorSurfaceVar, 1.0f);

    // Title & Stats
    std::wstring titleStr = L"Медиатека (" + std::to_wstring(tracks.size()) + L" треков)";
    g.DrawString(titleStr.c_str(), -1, &fontHeader, PointF(x + 24.0f, y + 20.0f), &textBrush);

    if (tracks.empty()) {
        Font fontEmpty(L"Segoe UI", 12, FontStyleRegular, UnitPixel);
        g.DrawString(L"Медиатека пуста. Перетащите файлы/папки сюда или нажмите «Добавить папку»", -1, &fontEmpty, PointF(x + 24.0f, y + 80.0f), &dimBrush);
        return;
    }

    int startY = y + 60;
    int rowHeight = 48;
    int visibleCount = (h - 70) / rowHeight;

    for (size_t i = 0; i < tracks.size(); ++i) {
        int rowY = startY + static_cast<int>(i) * rowHeight - m_scrollOffset;
        if (rowY + rowHeight < startY || rowY > y + h) continue;

        const auto& t = tracks[i];
        bool isPlaying = (static_cast<int>(i) == currentPlayIdx);
        bool isHover = (static_cast<int>(i) == m_hoverTrackIndex);

        if (isPlaying) {
            SolidBrush playBg(Color(30, 0, 245, 212));
            g.FillRectangle(&playBg, x + 16, rowY, w - 32, rowHeight - 4);
            Pen playBorder(m_colorAccent, 1.0f);
            g.DrawRectangle(&playBorder, x + 16, rowY, w - 32, rowHeight - 4);
        } else if (isHover) {
            SolidBrush hoverBg(Color(15, 255, 255, 255));
            g.FillRectangle(&hoverBg, x + 16, rowY, w - 32, rowHeight - 4);
        }

        // Play Icon / Number
        std::wstring numStr = isPlaying ? L"▶" : std::to_wstring(i + 1);
        g.DrawString(numStr.c_str(), -1, &fontArtist, PointF(x + 28.0f, rowY + 14.0f), isPlaying ? &accentBrush : &dimBrush);

        // Title & Artist
        std::wstring wTitle = toWide(t.title);
        std::wstring wArtist = toWide(t.artist);
        g.DrawString(wTitle.c_str(), -1, &fontTrack, PointF(x + 60.0f, rowY + 6.0f), isPlaying ? &accentBrush : &textBrush);
        g.DrawString(wArtist.c_str(), -1, &fontArtist, PointF(x + 60.0f, rowY + 24.0f), &dimBrush);

        // Hi-Res Badge
        if (t.isHiRes) {
            SolidBrush badgeBg(Color(40, 255, 159, 28));
            g.FillRectangle(&badgeBg, x + w - 180, rowY + 12, 50, 18);
            Pen badgeBorder(m_colorOrange, 1.0f);
            g.DrawRectangle(&badgeBorder, x + w - 180, rowY + 12, 50, 18);
            g.DrawString(L"HI-RES", -1, &fontBadge, PointF(x + w - 174.0f, rowY + 14.0f), &orangeBrush);
        }

        // Format info (FLAC / MP3)
        std::wstring fmtStr = toWide(t.format) + L" " + std::to_wstring(t.sampleRate / 1000) + L"k";
        g.DrawString(fmtStr.c_str(), -1, &fontBadge, PointF(x + w - 120.0f, rowY + 14.0f), &dimBrush);

        // Duration
        std::wstring durStr = toWide(formatTime(t.durationMs));
        g.DrawString(durStr.c_str(), -1, &fontArtist, PointF(x + w - 60.0f, rowY + 14.0f), &dimBrush);
    }
}

void MainWindow::renderLyricsView(Graphics& g, int x, int y, int w, int h) {
    Font fontHeader(L"Segoe UI", 16, FontStyleBold, UnitPixel);
    SolidBrush textBrush(m_colorText);
    SolidBrush dimBrush(m_colorTextDim);
    SolidBrush accentBrush(m_colorAccent);

    g.DrawString(L"Синхронизированный текст (Караоке)", -1, &fontHeader, PointF(x + 24.0f, y + 20.0f), &textBrush);

    if (m_currentLyrics.lines.empty()) {
        Font fontEmpty(L"Segoe UI", 12, FontStyleRegular, UnitPixel);
        g.DrawString(L"Текст песни не найден. Поместите .lrc файл рядом с аудиофайлом или укажите папку текстов.", -1, &fontEmpty, PointF(x + 24.0f, y + 80.0f), &dimBrush);
        return;
    }

    int64_t curMs = AudioEngineWin::getInstance().getPositionMs();
    int activeIdx = 0;
    for (size_t i = 0; i < m_currentLyrics.lines.size(); ++i) {
        if (m_currentLyrics.lines[i].timestampMs <= curMs) {
            activeIdx = static_cast<int>(i);
        } else {
            break;
        }
    }

    int centerY = y + h / 2 - 20;
    int lineHeight = 36;
    Font fontActive(L"Segoe UI", 15, FontStyleBold, UnitPixel);
    Font fontNormal(L"Segoe UI", 12, FontStyleRegular, UnitPixel);

    for (size_t i = 0; i < m_currentLyrics.lines.size(); ++i) {
        int lineY = centerY + (static_cast<int>(i) - activeIdx) * lineHeight;
        if (lineY < y + 60 || lineY > y + h - 20) continue;

        std::wstring wText = toWide(m_currentLyrics.lines[i].text);
        if (static_cast<int>(i) == activeIdx) {
            SolidBrush activeGlow(Color(30, 0, 245, 212));
            g.FillRectangle(&activeGlow, x + 20, lineY - 4, w - 40, 30);
            g.DrawString(wText.c_str(), -1, &fontActive, PointF(x + 30.0f, static_cast<REAL>(lineY)), &accentBrush);
        } else {
            g.DrawString(wText.c_str(), -1, &fontNormal, PointF(x + 30.0f, static_cast<REAL>(lineY)), &dimBrush);
        }
    }
}

void MainWindow::renderEqualizerView(Graphics& g, int x, int y, int w, int h) {
    Font fontHeader(L"Segoe UI", 16, FontStyleBold, UnitPixel);
    Font fontBand(L"Segoe UI", 9, FontStyleBold, UnitPixel);
    Font fontGain(L"Segoe UI", 9, FontStyleRegular, UnitPixel);
    SolidBrush textBrush(m_colorText);
    SolidBrush dimBrush(m_colorTextDim);
    SolidBrush accentBrush(m_colorAccent);
    Pen accentPen(m_colorAccent, 2.0f);
    Pen trackPen(m_colorSurfaceVar, 4.0f);

    g.DrawString(L"10-полосный параметрический DSP Эквалайзер", -1, &fontHeader, PointF(x + 24.0f, y + 20.0f), &textBrush);

    auto& eq = AudioEngineWin::getInstance().getEqualizer();
    const char* bandLabels[] = { "31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz" };

    int sliderCount = 10;
    int sliderW = (w - 80) / sliderCount;
    int sliderTop = y + 90;
    int sliderHeight = h - 220;

    for (int i = 0; i < sliderCount; ++i) {
        int sliderX = x + 40 + i * sliderW + sliderW / 2;
        float gain = eq.getBandGain(i); // -12dB to +12dB

        // Vertical track
        g.DrawLine(&trackPen, sliderX, sliderTop, sliderX, sliderTop + sliderHeight);

        // Center line (0 dB)
        int zeroY = sliderTop + sliderHeight / 2;
        Pen zeroPen(m_colorSurfaceVar, 1.0f);
        g.DrawLine(&zeroPen, sliderX - 10, zeroY, sliderX + 10, zeroY);

        // Thumb position
        float norm = (gain + 12.0f) / 24.0f; // 0.0 to 1.0
        int thumbY = sliderTop + sliderHeight - static_cast<int>(norm * sliderHeight);

        SolidBrush thumbBrush(m_colorAccent);
        g.FillEllipse(&thumbBrush, sliderX - 8, thumbY - 8, 16, 16);

        // Gain text
        std::wstring gainStr = (gain > 0 ? L"+" : L"") + std::to_wstring(static_cast<int>(gain)) + L" dB";
        g.DrawString(gainStr.c_str(), -1, &fontGain, PointF(sliderX - 16.0f, sliderTop - 20.0f), &accentBrush);

        // Frequency Label
        std::wstring freqStr = toWide(bandLabels[i]);
        g.DrawString(freqStr.c_str(), -1, &fontBand, PointF(sliderX - 14.0f, sliderTop + sliderHeight + 12.0f), &dimBrush);
    }

    // Presets bar
    int presetY = sliderTop + sliderHeight + 50;
    const wchar_t* presets[] = { L"Плоский (Flat)", L"Бас (Bass Boost)", L"Вокал", L"Рок", L"Джаз", L"Студийный" };
    for (int p = 0; p < 6; ++p) {
        int px = x + 30 + p * 120;
        SolidBrush pBg(m_colorSurface);
        g.FillRectangle(&pBg, px, presetY, 110, 30);
        Pen pBorder(m_colorSurfaceVar, 1.0f);
        g.DrawRectangle(&pBorder, px, presetY, 110, 30);
        Font fontP(L"Segoe UI", 9, FontStyleRegular, UnitPixel);
        g.DrawString(presets[p], -1, &fontP, PointF(px + 10.0f, presetY + 8.0f), &textBrush);
    }
}

void MainWindow::renderBottomPlayerBar(Graphics& g, int x, int y, int w, int h) {
    SolidBrush surfaceBrush(m_colorSurface);
    g.FillRectangle(&surfaceBrush, x, y, w, h);

    Pen borderPen(m_colorSurfaceVar, 1.0f);
    g.DrawLine(&borderPen, x, y, x + w, y);

    const TrackInfo& cur = AudioEngineWin::getInstance().getCurrentTrack();
    int64_t posMs = AudioEngineWin::getInstance().getPositionMs();
    int64_t durMs = cur.durationMs;
    PlaybackState state = AudioEngineWin::getInstance().getState();

    Font fontTitle(L"Segoe UI", 11, FontStyleBold, UnitPixel);
    Font fontArtist(L"Segoe UI", 10, FontStyleRegular, UnitPixel);
    Font fontTime(L"Segoe UI", 9, FontStyleRegular, UnitPixel);
    Font fontBtn(L"Segoe UI", 14, FontStyleBold, UnitPixel);

    SolidBrush textBrush(m_colorText);
    SolidBrush dimBrush(m_colorTextDim);
    SolidBrush accentBrush(m_colorAccent);
    Pen accentPen(m_colorAccent, 3.0f);
    Pen trackPen(m_colorSurfaceVar, 3.0f);

    // 1. Current Track Info (Left)
    if (!cur.filePath.empty()) {
        std::wstring wTitle = toWide(cur.title);
        std::wstring wArtist = toWide(cur.artist);
        g.DrawString(wTitle.c_str(), -1, &fontTitle, PointF(x + 20.0f, y + 24.0f), &textBrush);
        g.DrawString(wArtist.c_str(), -1, &fontArtist, PointF(x + 20.0f, y + 44.0f), &dimBrush);

        // Hi-Res Badge
        if (cur.isHiRes) {
            SolidBrush bBg(Color(40, 255, 159, 28));
            g.FillRectangle(&bBg, x + 20, y + 64, 45, 14);
            Font fMini(L"Segoe UI", 7, FontStyleBold, UnitPixel);
            SolidBrush oBrush(m_colorOrange);
            g.DrawString(L"HI-RES", -1, &fMini, PointF(x + 24.0f, y + 65.0f), &oBrush);
        }
    } else {
        g.DrawString(L"Воспроизведение остановлено", -1, &fontTitle, PointF(x + 20.0f, y + 36.0f), &dimBrush);
    }

    // 2. Playback Controls (Center)
    int centerX = x + w / 2;
    int btnY = y + 16;

    // Previous
    g.DrawString(L"⏮", -1, &fontBtn, PointF(centerX - 60.0f, static_cast<REAL>(btnY)), &textBrush);

    // Play/Pause Circle
    SolidBrush playBg(m_colorAccent);
    g.FillEllipse(&playBg, centerX - 18, btnY - 2, 36, 36);
    SolidBrush iconBrush(Color(255, 10, 10, 10));
    std::wstring playIcon = (state == PlaybackState::PLAYING) ? L"⏸" : L"▶";
    g.DrawString(playIcon.c_str(), -1, &fontBtn, PointF(centerX - 10.0f, static_cast<REAL>(btnY + 6)), &iconBrush);

    // Next
    g.DrawString(L"⏭", -1, &fontBtn, PointF(centerX + 35.0f, static_cast<REAL>(btnY)), &textBrush);

    // 3. Progress Slider (Timeline)
    int seekX = centerX - 180;
    int seekW = 360;
    int seekY = y + 58;

    g.DrawLine(&trackPen, seekX, seekY, seekX + seekW, seekY);

    float progress = (durMs > 0) ? (static_cast<float>(posMs) / durMs) : 0.0f;
    progress = (std::max)(0.0f, (std::min)(1.0f, progress));
    int curSeekX = seekX + static_cast<int>(progress * seekW);

    g.DrawLine(&accentPen, seekX, seekY, curSeekX, seekY);
    g.FillEllipse(&playBg, curSeekX - 5, seekY - 5, 10, 10);

    // Times
    std::wstring curTimeStr = toWide(formatTime(posMs));
    std::wstring durTimeStr = toWide(formatTime(durMs));
    g.DrawString(curTimeStr.c_str(), -1, &fontTime, PointF(seekX - 40.0f, seekY - 7.0f), &dimBrush);
    g.DrawString(durTimeStr.c_str(), -1, &fontTime, PointF(seekX + seekW + 12.0f, seekY - 7.0f), &dimBrush);

    // 4. Volume Slider (Right)
    int volX = x + w - 140;
    int volW = 90;
    int volY = y + 42;

    float vol = AudioEngineWin::getInstance().getVolume();
    std::wstring volIcon = AudioEngineWin::getInstance().isMuted() ? L"🔇" : L"🔊";
    g.DrawString(volIcon.c_str(), -1, &fontTitle, PointF(volX - 28.0f, volY - 10.0f), &dimBrush);

    g.DrawLine(&trackPen, volX, volY, volX + volW, volY);
    int curVolX = volX + static_cast<int>(vol * volW);
    g.DrawLine(&accentPen, volX, volY, curVolX, volY);
    g.FillEllipse(&playBg, curVolX - 4, volY - 4, 8, 8);
}

void MainWindow::onMouseDown(int x, int y, bool isRightClick) {
    RECT rc;
    GetClientRect(m_hWnd, &rc);
    int w = rc.right - rc.left;
    int h = rc.bottom - rc.top;

    int sidebarWidth = 200;
    int bottomBarHeight = 90;

    // 1. Sidebar clicks
    if (x < sidebarWidth && y < h - bottomBarHeight) {
        for (int i = 0; i < 3; ++i) {
            int tabY = 80 + i * 44;
            if (y >= tabY && y <= tabY + 36) {
                m_currentTab = static_cast<ViewTab>(i);
                InvalidateRect(m_hWnd, nullptr, FALSE);
                return;
            }
        }

        // Add Folder Button
        int btnY = (h - bottomBarHeight) - 90;
        if (y >= btnY && y <= btnY + 32) {
            BROWSEINFOW bi = {0};
            bi.hwndOwner = m_hWnd;
            bi.lpszTitle = L"Выберите папку с музыкой (FLAC, MP3, WAV)";
            bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
            LPITEMIDLIST pidl = SHBrowseForFolderW(&bi);
            if (pidl) {
                wchar_t path[MAX_PATH];
                if (SHGetPathFromIDListW(pidl, path)) {
                    char charPath[MAX_PATH];
                    WideCharToMultiByte(CP_UTF8, 0, path, -1, charPath, MAX_PATH, nullptr, nullptr);
                    LibraryManager::getInstance().scanDirectory(charPath);
                }
                CoTaskMemFree(pidl);
                InvalidateRect(m_hWnd, nullptr, FALSE);
            }
            return;
        }

        // Lyrics Folder Button
        int scanY = btnY + 40;
        if (y >= scanY && y <= scanY + 32) {
            BROWSEINFOW bi = {0};
            bi.hwndOwner = m_hWnd;
            bi.lpszTitle = L"Выберите папку с текстами песен (.lrc, .txt)";
            bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
            LPITEMIDLIST pidl = SHBrowseForFolderW(&bi);
            if (pidl) {
                wchar_t path[MAX_PATH];
                if (SHGetPathFromIDListW(pidl, path)) {
                    char charPath[MAX_PATH];
                    WideCharToMultiByte(CP_UTF8, 0, path, -1, charPath, MAX_PATH, nullptr, nullptr);
                    LibraryManager::getInstance().setCustomLyricsDir(charPath);
                    m_lastTrackPath = ""; // Force reload lyrics
                }
                CoTaskMemFree(pidl);
                InvalidateRect(m_hWnd, nullptr, FALSE);
            }
            return;
        }
    }

    // 2. Bottom Player Bar clicks
    if (y >= h - bottomBarHeight) {
        int centerX = w / 2;
        int btnY = (h - bottomBarHeight) + 16;

        // Play/Pause
        if (x >= centerX - 20 && x <= centerX + 20 && y >= btnY - 5 && y <= btnY + 35) {
            AudioEngineWin::getInstance().togglePlayPause();
            InvalidateRect(m_hWnd, nullptr, FALSE);
            return;
        }

        // Prev
        if (x >= centerX - 65 && x <= centerX - 35 && y >= btnY && y <= btnY + 30) {
            LibraryManager::getInstance().playPrevious();
            InvalidateRect(m_hWnd, nullptr, FALSE);
            return;
        }

        // Next
        if (x >= centerX + 30 && x <= centerX + 60 && y >= btnY && y <= btnY + 30) {
            LibraryManager::getInstance().playNext();
            InvalidateRect(m_hWnd, nullptr, FALSE);
            return;
        }

        // Seeker Bar
        int seekX = centerX - 180;
        int seekW = 360;
        int seekY = (h - bottomBarHeight) + 58;
        if (x >= seekX && x <= seekX + seekW && y >= seekY - 10 && y <= seekY + 10) {
            m_isDraggingSeek = true;
            float p = static_cast<float>(x - seekX) / seekW;
            int64_t target = static_cast<int64_t>(p * AudioEngineWin::getInstance().getDurationMs());
            AudioEngineWin::getInstance().seekTo(target);
            InvalidateRect(m_hWnd, nullptr, FALSE);
            return;
        }

        // Volume Bar
        int volX = w - 140;
        int volW = 90;
        int volY = (h - bottomBarHeight) + 42;
        if (x >= volX - 30 && x <= volX && y >= volY - 10 && y <= volY + 10) {
            AudioEngineWin::getInstance().toggleMute();
            InvalidateRect(m_hWnd, nullptr, FALSE);
            return;
        }
        if (x >= volX && x <= volX + volW && y >= volY - 10 && y <= volY + 10) {
            m_isDraggingVolume = true;
            float v = static_cast<float>(x - volX) / volW;
            AudioEngineWin::getInstance().setVolume(v);
            InvalidateRect(m_hWnd, nullptr, FALSE);
            return;
        }
    }

    // 3. View Specific clicks
    if (m_currentTab == ViewTab::LIBRARY && x >= sidebarWidth && y < h - bottomBarHeight) {
        int startY = 60;
        int rowHeight = 48;
        int clickedIdx = (y - startY + m_scrollOffset) / rowHeight;
        if (clickedIdx >= 0 && clickedIdx < static_cast<int>(LibraryManager::getInstance().getTracks().size())) {
            LibraryManager::getInstance().playIndex(clickedIdx);
            InvalidateRect(m_hWnd, nullptr, FALSE);
        }
    } else if (m_currentTab == ViewTab::LYRICS && x >= sidebarWidth && y < h - bottomBarHeight) {
        // Tap to Seek in Lyrics!
        int centerY = (h - bottomBarHeight) / 2 - 20;
        int lineHeight = 36;
        int64_t curMs = AudioEngineWin::getInstance().getPositionMs();
        int activeIdx = 0;
        for (size_t i = 0; i < m_currentLyrics.lines.size(); ++i) {
            if (m_currentLyrics.lines[i].timestampMs <= curMs) activeIdx = static_cast<int>(i);
        }

        int clickedLine = activeIdx + (y - centerY) / lineHeight;
        if (clickedLine >= 0 && clickedLine < static_cast<int>(m_currentLyrics.lines.size())) {
            AudioEngineWin::getInstance().seekTo(m_currentLyrics.lines[clickedLine].timestampMs);
            InvalidateRect(m_hWnd, nullptr, FALSE);
        }
    } else if (m_currentTab == ViewTab::EQUALIZER && x >= sidebarWidth && y < h - bottomBarHeight) {
        int contentW = w - sidebarWidth;
        int sliderCount = 10;
        int sliderW = (contentW - 80) / sliderCount;
        int sliderTop = 90;
        int sliderHeight = (h - bottomBarHeight) - 220;

        for (int i = 0; i < sliderCount; ++i) {
            int sliderX = sidebarWidth + 40 + i * sliderW + sliderW / 2;
            if (x >= sliderX - 15 && x <= sliderX + 15 && y >= sliderTop && y <= sliderTop + sliderHeight) {
                m_draggingEqBand = i;
                float norm = 1.0f - static_cast<float>(y - sliderTop) / sliderHeight;
                float gain = (norm * 24.0f) - 12.0f;
                AudioEngineWin::getInstance().getEqualizer().setBandGain(i, gain);
                InvalidateRect(m_hWnd, nullptr, FALSE);
                return;
            }
        }

        // Preset buttons
        int presetY = sliderTop + sliderHeight + 50;
        if (y >= presetY && y <= presetY + 30) {
            int pIdx = (x - (sidebarWidth + 30)) / 120;
            if (pIdx >= 0 && pIdx < 6) {
                AudioEngineWin::getInstance().getEqualizer().loadStockPreset(pIdx);
                InvalidateRect(m_hWnd, nullptr, FALSE);
            }
        }
    }
}

void MainWindow::onMouseMove(int x, int y) {
    RECT rc;
    GetClientRect(m_hWnd, &rc);
    int w = rc.right - rc.left;
    int h = rc.bottom - rc.top;

    int sidebarWidth = 200;
    int bottomBarHeight = 90;

    if (m_isDraggingSeek) {
        int centerX = w / 2;
        int seekX = centerX - 180;
        int seekW = 360;
        float p = static_cast<float>(x - seekX) / seekW;
        p = (std::max)(0.0f, (std::min)(1.0f, p));
        int64_t target = static_cast<int64_t>(p * AudioEngineWin::getInstance().getDurationMs());
        AudioEngineWin::getInstance().seekTo(target);
        InvalidateRect(m_hWnd, nullptr, FALSE);
    } else if (m_isDraggingVolume) {
        int volX = w - 140;
        int volW = 90;
        float v = static_cast<float>(x - volX) / volW;
        v = (std::max)(0.0f, (std::min)(1.0f, v));
        AudioEngineWin::getInstance().setVolume(v);
        InvalidateRect(m_hWnd, nullptr, FALSE);
    } else if (m_draggingEqBand >= 0) {
        int sliderTop = 90;
        int sliderHeight = (h - bottomBarHeight) - 220;
        float norm = 1.0f - static_cast<float>(y - sliderTop) / sliderHeight;
        norm = (std::max)(0.0f, (std::min)(1.0f, norm));
        float gain = (norm * 24.0f) - 12.0f;
        AudioEngineWin::getInstance().getEqualizer().setBandGain(m_draggingEqBand, gain);
        InvalidateRect(m_hWnd, nullptr, FALSE);
    } else if (m_currentTab == ViewTab::LIBRARY && x >= sidebarWidth && y < h - bottomBarHeight) {
        int startY = 60;
        int rowHeight = 48;
        int hover = (y - startY + m_scrollOffset) / rowHeight;
        if (hover != m_hoverTrackIndex) {
            m_hoverTrackIndex = hover;
            InvalidateRect(m_hWnd, nullptr, FALSE);
        }
    }
}

void MainWindow::onMouseUp(int x, int y) {
    m_isDraggingSeek = false;
    m_isDraggingVolume = false;
    m_draggingEqBand = -1;
}

void MainWindow::onMouseWheel(int delta) {
    m_scrollOffset -= delta / 2;
    if (m_scrollOffset < 0) m_scrollOffset = 0;
    InvalidateRect(m_hWnd, nullptr, FALSE);
}

void MainWindow::onDropFiles(HDROP hDrop) {
    UINT count = DragQueryFileW(hDrop, 0xFFFFFFFF, nullptr, 0);
    for (UINT i = 0; i < count; ++i) {
        wchar_t filePath[MAX_PATH];
        if (DragQueryFileW(hDrop, i, filePath, MAX_PATH)) {
            char utf8Path[MAX_PATH];
            WideCharToMultiByte(CP_UTF8, 0, filePath, -1, utf8Path, MAX_PATH, nullptr, nullptr);

            DWORD attrs = GetFileAttributesW(filePath);
            if (attrs != INVALID_FILE_ATTRIBUTES && (attrs & FILE_ATTRIBUTE_DIRECTORY)) {
                LibraryManager::getInstance().scanDirectory(utf8Path);
            } else {
                LibraryManager::getInstance().addTrack(utf8Path);
            }
        }
    }
    DragFinish(hDrop);
    InvalidateRect(m_hWnd, nullptr, FALSE);
}
