# CMakeLists.txt for CPNT2 Android JNI
# 在 Android Studio 中使用, 或通过 NDK 独立交叉编译。
# 注意: Gradle 打包使用 app/src/main/jniLibs 下的预编译 .so
# (由仓库根目录 script/build_android.bat 生成), 本文件仅在需要源码编译时使用。
#
# 用法 (在仓库根目录):
#   mkdir build_android_arm64 && cd build_android_arm64
#   cmake -G "MinGW Makefiles" \
#     -DCMAKE_TOOLCHAIN_FILE=D:/Software/android-ndk-r27c/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
#     -DCMAKE_BUILD_TYPE=Release ../android/app/src/main/jni
#   mingw32-make -j4

cmake_minimum_required(VERSION 3.18.1)
project(cpnt2_android C)

# NDK clang 比 GCC 更严格, 全局强制包含 complex.h
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -include complex.h")

# ========== CPNT2 源码路径 ==========
# 本文件位于 <仓库根>/android/app/src/main/jni, 上溯 5 级即仓库根目录
get_filename_component(CPNT2_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../" ABSOLUTE)
set(CPNT2_SRC_DIR ${CPNT2_ROOT_DIR}/src)

# codebook 预生成文件 (由 Windows/Linux 主机构建生成, 不随 git 分发)
set(CODEBOOK_GEN_DIR ${CPNT2_ROOT_DIR}/build_windows/src)

if(NOT EXISTS ${CODEBOOK_GEN_DIR}/codebook.c)
    message(FATAL_ERROR
        "未找到预生成 codebook: ${CODEBOOK_GEN_DIR}/codebook.c\n"
        "请先在本机完成一次 Windows/Linux 构建以生成 codebook*.c, "
        "或参考仓库根目录 CMakeLists_android.txt")
endif()

# ========== 生成配置头文件 ==========
set(CONFIG_DIR ${CMAKE_CURRENT_BINARY_DIR}/generated)
file(MAKE_DIRECTORY ${CONFIG_DIR})
file(MAKE_DIRECTORY ${CONFIG_DIR}/codec2)

file(WRITE ${CONFIG_DIR}/config.h
"#ifndef CONFIG_H
#define CONFIG_H
#define CODEC2_VERSION \"1.1.0\"
#define HAVE_MALLOC_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_MATH_H 1
#define HAVE_MEMCPY 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMSET 1
#endif
")

file(WRITE ${CONFIG_DIR}/codec2/version.h
"#ifndef CODEC2_VERSION_H
#define CODEC2_VERSION_H
#define CODEC2_VERSION_MAJOR 1
#define CODEC2_VERSION_MINOR 1
#define CODEC2_VERSION_PATCH 0
#define CODEC2_VERSION \"1.1.0\"
#endif
")

# ========== CPNT2 核心语音编解码源文件 ==========
# (仅编解码所需, 不含 FreeDV/OFDM/FSK 调制解调器; 与 script/build_android.bat 实际使用的配置一致)
set(CPNT2_SOURCES
    ${CPNT2_SRC_DIR}/codec2.c
    ${CPNT2_SRC_DIR}/codec2_fft.c
    ${CPNT2_SRC_DIR}/kiss_fft.c
    ${CPNT2_SRC_DIR}/kiss_fftr.c
    ${CPNT2_SRC_DIR}/dump.c
    ${CPNT2_SRC_DIR}/lpc.c
    ${CPNT2_SRC_DIR}/nlp.c
    ${CPNT2_SRC_DIR}/postfilter.c
    ${CPNT2_SRC_DIR}/sine.c
    ${CPNT2_SRC_DIR}/interp.c
    ${CPNT2_SRC_DIR}/lsp.c
    ${CPNT2_SRC_DIR}/phase.c
    ${CPNT2_SRC_DIR}/quantise.c
    ${CPNT2_SRC_DIR}/pack.c
    ${CPNT2_SRC_DIR}/newamp1.c
    ${CPNT2_SRC_DIR}/newamp2.c
    ${CPNT2_SRC_DIR}/mbest.c
    ${CPNT2_SRC_DIR}/linreg.c
    ${CPNT2_SRC_DIR}/speech_code.c
    ${CODEBOOK_GEN_DIR}/codebook.c
    ${CODEBOOK_GEN_DIR}/codebookd.c
    ${CODEBOOK_GEN_DIR}/codebookjmv.c
    ${CODEBOOK_GEN_DIR}/codebookge.c
    ${CODEBOOK_GEN_DIR}/codebooknewamp1.c
    ${CODEBOOK_GEN_DIR}/codebooknewamp1_energy.c
    ${CODEBOOK_GEN_DIR}/codebooknewamp2.c
    ${CODEBOOK_GEN_DIR}/codebooknewamp2_energy.c
)

# ========== 编译 CPNT2 库 ==========
add_library(cpnt2 SHARED ${CPNT2_SOURCES})

target_include_directories(cpnt2 PUBLIC
    ${CPNT2_SRC_DIR}
    ${CONFIG_DIR}
)

# 编译定义
target_compile_definitions(cpnt2 PRIVATE
    FLOATING_POINT
    VAR_ARRAYS
    LARGE_N
)

# NDK clang 比 GCC 更严格, 强制包含 complex.h 解决 crealf/cimagf 等声明
target_compile_options(cpnt2 PRIVATE -include complex.h)

# ARM 优化
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_definitions(cpnt2 PRIVATE __REAL__ FDV_ARM_MATH)
endif()

target_link_libraries(cpnt2 PUBLIC m log)

# ========== JNI 包装层 ==========
add_library(cpnt2_jni SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/cpnt2_jni.c
)

target_include_directories(cpnt2_jni PRIVATE
    ${CPNT2_SRC_DIR}
    ${CONFIG_DIR}
)

# 链接 CPNT2 库和 Android 日志库
target_link_libraries(cpnt2_jni
    cpnt2
    log
)

# ========== 交叉编译 Demo (可选) ==========
# 独立的命令行测试程序 (仅在非 Android 平台时编译)
if(NOT ANDROID)
    add_executable(cpnt2_android_demo
        ${CPNT2_ROOT_DIR}/demo/cpnt2_demo.c
    )
    target_link_libraries(cpnt2_android_demo cpnt2)
    target_include_directories(cpnt2_android_demo PRIVATE
        ${CPNT2_SRC_DIR}
        ${CONFIG_DIR}
    )
endif()
