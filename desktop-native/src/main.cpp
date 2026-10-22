#include <windows.h>
#include "AudioEngineWin.h"
#include "LibraryManager.h"
#include "MainWindowD2D.h"

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    // 1. High DPI Awareness (Per Monitor V2)
    typedef BOOL(WINAPI* SetProcessDpiAwarenessContextProc)(DPI_AWARENESS_CONTEXT);
    HMODULE hUser32 = GetModuleHandleW(L"user32.dll");
    if (hUser32) {
        SetProcessDpiAwarenessContextProc setDpi = (SetProcessDpiAwarenessContextProc)GetProcAddress(hUser32, "SetProcessDpiAwarenessContext");
        if (setDpi) {
            setDpi(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        }
    }

    // 2. Initialize Low-Latency Audio Engine
    if (!AudioEngineWin::getInstance().init()) {
        MessageBoxW(nullptr, L"Не удалось инициализировать аудиоустройство Windows.", L"FlacPlayer", MB_ICONERROR);
        return 1;
    }

    // 3. Create Hardware-Accelerated Direct2D Window
    if (!MainWindowD2D::getInstance().create(hInstance, 1120, 740)) {
        MessageBoxW(nullptr, L"Не удалось инициализировать графический интерфейс Direct2D.", L"FlacPlayer", MB_ICONERROR);
        return 1;
    }

    // 4. Windows Message Loop
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    // 5. Cleanup
    AudioEngineWin::getInstance().shutdown();
    return static_cast<int>(msg.wParam);
}
