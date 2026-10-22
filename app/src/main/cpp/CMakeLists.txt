cmake_minimum_required(VERSION 3.22.1)

project("audioplayer")

# Find the log library for Android
find_library(
        log-lib
        log)

# Add Oboe library via Prefab
find_package(oboe REQUIRED CONFIG)

# Add our source files
add_library(
        audioplayer
        SHARED
        native-lib.cpp
        AudioEngine.cpp
        AudioDecoder.cpp
        BiquadFilter.cpp
        EqualizerEngine.cpp)

# Link our library with Oboe and Android log
target_link_libraries(
        audioplayer
        oboe::oboe
        ${log-lib})
