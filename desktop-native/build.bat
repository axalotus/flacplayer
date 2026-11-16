@echo off
setlocal
echo =======================================================
echo   Building Modern FlacPlayer Native C++ (Direct2D Win64)
echo =======================================================

if "%COMPILER_DIR%"=="" set COMPILER_DIR=%LOCALAPPDATA%\Microsoft\WinGet\Packages\MartinStorsjo.LLVM-MinGW.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\llvm-mingw-20260616-ucrt-x86_64\bin
set GPP="%COMPILER_DIR%\g++.exe"
set WINDRES="%COMPILER_DIR%\windres.exe"

set SRC_DIR=%~dp0src
set OUT_DIR=C:\flacplayer\desktop
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
set OUT_EXE=%OUT_DIR%\FlacPlayer.exe

echo [1/2] Compiling Windows Resources (App Icon)...
%WINDRES% "%SRC_DIR%\app.rc" -O coff -o "%SRC_DIR%\app_res.o"

echo [2/2] Compiling Modern Direct2D C++ Executable...
%GPP% -std=c++17 -O3 -mwindows ^
  "%SRC_DIR%\main.cpp" ^
  "%SRC_DIR%\MainWindowD2D.cpp" ^
  "%SRC_DIR%\AudioEngineWin.cpp" ^
  "%SRC_DIR%\AudioDecoder.cpp" ^
  "%SRC_DIR%\LibraryManager.cpp" ^
  "%SRC_DIR%\BiquadFilter.cpp" ^
  "%SRC_DIR%\EqualizerEngine.cpp" ^
  "%SRC_DIR%\app_res.o" ^
  -I"%SRC_DIR%" ^
  -o "%OUT_EXE%" ^
  -ld2d1 -ldwrite -lwinmm -lshell32 -lole32 -lcomdlg32

if exist "%OUT_EXE%" (
    echo.
    echo =======================================================
    echo   SUCCESS: %OUT_EXE%
    echo =======================================================
) else (
    echo.
    echo   BUILD FAILED!
)
pause
