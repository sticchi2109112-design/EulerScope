cmake_minimum_required(VERSION 3.22.1)
project(eulerscope LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(eulerscope SHARED
        jni_bridge.cpp
        riesz.cpp
        vitals.cpp)

find_library(log-lib log)
target_link_libraries(eulerscope ${log-lib})

target_compile_options(eulerscope PRIVATE
        -O3
        -ffast-math
        -fvisibility=hidden
        -Wall)
