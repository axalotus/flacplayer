#pragma once

#include <windows.h>
#include <gdiplus.h>
#include <string>
#include <vector>
#include "AudioEngineWin.h"
#include "LibraryManager.h"
#include "LyricsEngine.h"

enum class ViewTab {
    LIBRARY,
    LYRICS,
    EQUALIZER
};

class MainWindow {
public:
    static MainWindow& getInstance();

    bool create(HINSTANCE hInstance, int width = 1050, int height = 680);
    HWND getHwnd() const { return m_hWnd; }

private:
    MainWindow();
    ~MainWindow();

    static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam);
    LRESULT handleMessage(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam);

    void onPaint(HDC hdc);
    void render(Gdiplus::Graphics& g, int width, int height);

    void renderSidebar(Gdiplus::Graphics& g, int x, int y, int w, int h);
    void renderLibraryView(Gdiplus::Graphics& g, int x, int y, int w, int h);
    void renderLyricsView(Gdiplus::Graphics& g, int x, int y, int w, int h);
    void renderEqualizerView(Gdiplus::Graphics& g, int x, int y, int w, int h);
    void renderBottomPlayerBar(Gdiplus::Graphics& g, int x, int y, int w, int h);

    void onMouseDown(int x, int y, bool isRightClick = false);
    void onMouseMove(int x, int y);
    void onMouseUp(int x, int y);
    void onMouseWheel(int delta);
    void onDropFiles(HDROP hDrop);

    HWND m_hWnd = nullptr;
    HINSTANCE m_hInstance = nullptr;
    ULONG_PTR m_gdiplusToken = 0;

    ViewTab m_currentTab = ViewTab::LIBRARY;
    int m_scrollOffset = 0;
    int m_hoverTrackIndex = -1;
    bool m_isDraggingSeek = false;
    bool m_isDraggingVolume = false;
    int m_draggingEqBand = -1;

    LyricsData m_currentLyrics;
    std::string m_lastTrackPath;

    // Fonts & Colors
    Gdiplus::Color m_colorBg{255, 10, 10, 10};
    Gdiplus::Color m_colorSurface{255, 20, 20, 20};
    Gdiplus::Color m_colorSurfaceVar{255, 30, 30, 30};
    Gdiplus::Color m_colorAccent{255, 0, 245, 212};
    Gdiplus::Color m_colorText{255, 235, 235, 235};
    Gdiplus::Color m_colorTextDim{255, 140, 140, 140};
    Gdiplus::Color m_colorOrange{255, 255, 159, 28};
    Gdiplus::Color m_colorPink{255, 247, 37, 133};
};
