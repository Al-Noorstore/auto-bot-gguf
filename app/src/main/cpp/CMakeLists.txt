# Auto Bot GGUF Engine — llama.cpp v0.5.0
# llama.cpp source CI mein app/src/main/cpp/llama.cpp par clone hoti hai (pinned: v0.5.0)
cmake_minimum_required(VERSION 3.22.1)
project(autobot_gguf)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    add_subdirectory(llama.cpp)
    add_library(autobot-jni SHARED llama-jni.cpp)
    target_link_libraries(autobot-jni llama log)
else()
    # llama.cpp source na ho (local dev) to empty stub — CI par asli build hoti hai
    add_library(autobot-jni SHARED llama-stub.cpp)
    target_link_libraries(autobot-jni log)
endif()
