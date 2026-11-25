# ketvoice_wasapi.dll: exclusive-mode WASAPI output for Ketvoice for Windows's « Mode exclusif » (bit-perfect).
# C ABI, loaded with JNA (desktop/.../audio/WasapiSink.kt). Built on windows-latest with MSVC:
#   cmake -S native/wasapi -B native/wasapi/build -A x64
#   cmake --build native/wasapi/build --config Release
cmake_minimum_required(VERSION 3.20)
project(ketvoice_wasapi LANGUAGES CXX)

if(NOT MSVC)
  message(FATAL_ERROR "ketvoice_wasapi is Windows-only (MSVC + the Windows SDK's Core Audio headers)")
endif()

add_library(ketvoice_wasapi SHARED ketvoice_wasapi.cpp)
target_compile_features(ketvoice_wasapi PRIVATE cxx_std_17)
target_compile_options(ketvoice_wasapi PRIVATE /EHsc /permissive- /utf-8 /W4)
target_compile_definitions(ketvoice_wasapi PRIVATE UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN)
target_link_libraries(ketvoice_wasapi PRIVATE ole32 uuid)
# The JVM loads it from an extracted copy: link the CRT statically so no VC++ runtime is needed.
set_property(TARGET ketvoice_wasapi PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
