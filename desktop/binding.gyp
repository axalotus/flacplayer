{
  "targets": [
    {
      "target_name": "zuna_native",
      "sources": [
        "native/addon.cpp",
        "native/DesktopAudioEngine.cpp",
        "native/DesktopDecoder.cpp",
        "native/DesktopEqualizer.cpp",
        "native/LinuxMprisService.cpp",
        "../app/src/main/cpp/AlacDecoder.cpp",
        "../app/src/main/cpp/BiquadFilter.cpp"
      ],
      "include_dirs": [
        "native",
        "../app/src/main/cpp"
      ],
      "defines": [
        "NAPI_VERSION=8",
        "_USE_MATH_DEFINES",
        "NOMINMAX",
        "_CRT_SECURE_NO_WARNINGS"
      ],
      "cflags!": [ "-fno-exceptions" ],
      "cflags_cc!": [ "-fno-exceptions" ],
      "cflags_cc": [ "-std=c++17", "-O3" ],
      "conditions": [
        ["OS=='linux'", {
          "defines": [ "HAVE_FFMPEG=1" ],
          "cflags_cc": [ "-std=c++17", "-O3", "<!@(pkg-config --cflags gio-2.0 libavformat libavcodec libavutil libswresample)" ],
          "libraries": [ "-lpthread", "-ldl", "-lm", "<!@(pkg-config --libs gio-2.0 libavformat libavcodec libavutil libswresample)" ]
        }],
        ["OS=='win'", {
          "msvs_settings": {
            "VCCLCompilerTool": {
              "ExceptionHandling": 1,
              "AdditionalOptions": [ "/std:c++17", "/O2" ]
            }
          }
        }],
        ["OS=='mac'", {
          "xcode_settings": {
            "GCC_ENABLE_CPP_EXCEPTIONS": "YES",
            "CLANG_CXX_LANGUAGE_STANDARD": "c++17",
            "OTHER_LDFLAGS": [
              "-framework CoreFoundation",
              "-framework CoreAudio",
              "-framework AudioToolbox"
            ]
          }
        }]
      ]
    }
  ]
}
