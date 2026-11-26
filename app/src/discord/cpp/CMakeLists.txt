cmake_minimum_required(VERSION 3.22.1)
project(discordbridge CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Ketvoice TV builds without the Discord SDK (no Discord app on a TV): nothing to build there.
option(KETVOICE_WITHOUT_DISCORD "Build no Discord bridge" OFF)
if(KETVOICE_WITHOUT_DISCORD)
    return()
endif()

find_package(discord_partner_sdk REQUIRED CONFIG)

add_library(discordbridge SHARED discord_bridge.cpp)
target_link_libraries(discordbridge
    discord_partner_sdk::discord_partner_sdk
    android
    log)
