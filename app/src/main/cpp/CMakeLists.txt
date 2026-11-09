cmake_minimum_required(VERSION 3.22.1)
project("sky_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page alignment required for modern Android 15+ arm64-v8a devices
set(PAGE_ALIGN_FLAGS "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
find_library(android-lib android)

# 1. Cactus Needle 3 + Whistle Native Shared Library (libneedle_jni.so)
add_library(
    needle_jni
    SHARED
    needle_jni.cpp
)

# If prebuilt libneedle.a is present in jniLibs/arm64-v8a, link it directly
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libneedle.a")
    add_library(needle_static STATIC IMPORTED)
    set_target_properties(needle_static PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libneedle.a"
    )
    target_compile_definitions(needle_jni PRIVATE HAVE_PREBUILT_LIBNEEDLE=1)
    target_link_libraries(needle_jni needle_static ${log-lib} ${android-lib})
else()
    target_link_libraries(needle_jni ${log-lib} ${android-lib})
endif()

set_target_properties(needle_jni PROPERTIES
    LINK_FLAGS "${PAGE_ALIGN_FLAGS}"
)

# 2. llama.cpp GGUF Native Shared Library (libllama_jni.so)
add_library(
    llama_jni
    SHARED
    llama_jni.cpp
)

target_link_libraries(
    llama_jni
    ${log-lib}
    ${android-lib}
)

set_target_properties(llama_jni PROPERTIES
    LINK_FLAGS "${PAGE_ALIGN_FLAGS}"
)
