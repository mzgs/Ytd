cmake_minimum_required(VERSION 3.22.1)

project(ytdmp3 C)

find_library(log-lib log)

add_library(mp3lame STATIC IMPORTED)
set_target_properties(
    mp3lame
    PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libmp3lame.a
)

add_library(ytdmp3 SHARED mp3_encoder_jni.c)

set_target_properties(
    ytdmp3
    PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)

target_link_options(
    ytdmp3
    PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_link_libraries(
    ytdmp3
    PRIVATE
    mp3lame
    ${log-lib}
)

target_include_directories(
    ytdmp3
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)
