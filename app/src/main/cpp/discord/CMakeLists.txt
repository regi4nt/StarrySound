cmake_minimum_required(VERSION 3.18)
project(starry_discord CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The Discord Social SDK (Android) is downloaded from the Discord Developer Portal and unpacked into ./sdk
# (see README.md). Only the header and the native library for the current ABI are needed.
set(SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/sdk)

file(GLOB_RECURSE SDK_HEADERS "${SDK_DIR}/*discordpp.h")
if(NOT SDK_HEADERS)
    message(FATAL_ERROR "discordpp.h not found under ${SDK_DIR}. See app/src/main/cpp/discord/README.md")
endif()
list(GET SDK_HEADERS 0 SDK_HEADER)
get_filename_component(SDK_INCLUDE ${SDK_HEADER} DIRECTORY)

find_library(DISCORD_SDK_LIB NAMES discord_partner_sdk
    PATHS ${SDK_DIR}/lib/${ANDROID_ABI} ${SDK_DIR}/${ANDROID_ABI} ${SDK_DIR}/libs/${ANDROID_ABI}
    NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
if(NOT DISCORD_SDK_LIB)
    message(FATAL_ERROR "libdiscord_partner_sdk for ${ANDROID_ABI} not found under ${SDK_DIR}. See README.md")
endif()

add_library(starry_discord SHARED starry_discord.cpp)
target_include_directories(starry_discord PRIVATE ${SDK_INCLUDE})
target_link_libraries(starry_discord ${DISCORD_SDK_LIB} log)

# Android 15+ devices with 16 KB pages need 16 KB-aligned segments (NDK r27+ does this by default).
target_link_options(starry_discord PRIVATE "-Wl,-z,max-page-size=16384")
