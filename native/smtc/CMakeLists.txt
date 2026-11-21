# ketvoice_smtc.dll: System Media Transport Controls and the taskbar thumbnail toolbar for Ketvoice
# for Windows (C ABI, loaded with JNA).
# Built on windows-latest with MSVC and the Windows SDK's C++/WinRT headers:
#   cmake -S native/smtc -B native/smtc/build -A x64
#   cmake --build native/smtc/build --config Release
cmake_minimum_required(VERSION 3.20)
project(ketvoice_smtc LANGUAGES CXX)

if(NOT MSVC)
  message(FATAL_ERROR "ketvoice_smtc is Windows-only (MSVC + C++/WinRT)")
endif()

add_library(ketvoice_smtc SHARED ketvoice_smtc.cpp)
target_compile_features(ketvoice_smtc PRIVATE cxx_std_20)
target_compile_options(ketvoice_smtc PRIVATE /EHsc /permissive- /utf-8 /W4 /bigobj)
target_compile_definitions(ketvoice_smtc PRIVATE UNICODE _UNICODE NOMINMAX)
# The SDK's C++/WinRT projection (Include/<ver>/cppwinrt); Visual Studio usually lists it already.
if(CMAKE_VS_WINDOWS_TARGET_PLATFORM_VERSION)
  set(KV_PF86 "ProgramFiles(x86)")
  set(KV_CPPWINRT "$ENV{${KV_PF86}}/Windows Kits/10/Include/${CMAKE_VS_WINDOWS_TARGET_PLATFORM_VERSION}/cppwinrt")
  if(EXISTS "${KV_CPPWINRT}/winrt/base.h")
    target_include_directories(ketvoice_smtc SYSTEM PRIVATE "${KV_CPPWINRT}")
  endif()
endif()
target_link_libraries(ketvoice_smtc PRIVATE windowsapp user32 ole32 gdi32 dwmapi uuid)
# The JVM loads it from an extracted copy: link the CRT statically so no VC++ runtime is needed.
set_property(TARGET ketvoice_smtc PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
