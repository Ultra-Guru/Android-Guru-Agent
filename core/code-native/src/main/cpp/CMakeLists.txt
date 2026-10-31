cmake_minimum_required(VERSION 3.22.1)
project(code_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 与 :terminal-native 同款编译基线（-Wall -Wextra -O2）
add_compile_options(-Wall -Wextra -O2)

# 纯算法库（host 可测：单元测试经 host 编译器直接消费同一份源码）
add_library(code_native_core STATIC
    src/code_kernel.cpp
)
target_include_directories(code_native_core PUBLIC include)

# JNI 桥（Android 平台；host 编译由 CMake 护栏挡掉）
if(ANDROID)
    add_library(code_native SHARED
        src/code_native_jni.cpp
    )
    target_link_libraries(code_native PRIVATE code_native_core)
    target_include_directories(code_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
endif()

# host 单元测试（与 Kotlin StandardTextKernelTest 交叉验证；默认关闭，
# CI/开发者显式 -DAPEX_CODE_BUILD_TESTS=ON 才构建）
if(APEX_CODE_BUILD_TESTS AND NOT ANDROID)
    enable_testing()
    add_executable(code_native_test
        ${CMAKE_CURRENT_SOURCE_DIR}/../test/cpp/code_kernel_test.cpp
        src/code_kernel.cpp
    )
    target_include_directories(code_native_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    add_test(NAME code_native_host COMMAND code_native_test)
endif()
