cmake_minimum_required(VERSION 3.22.1)
project(discordbridge CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(discord_partner_sdk REQUIRED CONFIG)

add_library(discordbridge SHARED discord_bridge.cpp)
target_link_libraries(discordbridge
    discord_partner_sdk::discord_partner_sdk
    android
    log)
