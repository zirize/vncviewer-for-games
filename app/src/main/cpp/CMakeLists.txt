cmake_minimum_required(VERSION 3.22.1)

project("customvncviewer")

# ── libjpeg-turbo ───────────────────────────────────────────────────────────
# 🔴 **Deliberately NOT `add_subdirectory(libjpeg-turbo)`.**
#    Upstream blocks that with a `FATAL_ERROR` on purpose (see the section beginning "The
#    libjpeg-turbo build system has never supported..." in its CMakeLists.txt). There is no
#    opt-out, and that is intentional.
#
# 🔑 **Why the one offending line is not simply patched** — it used to be: the `FATAL_ERROR` was
#    changed to a `WARNING`. But that makes it **impossible to have libjpeg-turbo as a git
#    submodule**: anyone doing a fresh `git clone --recursive` gets clean upstream, has no patch,
#    and the build fails outright. A repository that makes people run `git apply` by hand is not
#    one you can hand to anybody else.
#
# ⇒ So it uses `ExternalProject_Add()`, which is what upstream points downstream projects at.
#    libjpeg-turbo is configured, built and installed separately, and only the result is linked.
#    Not one byte of its source is modified.
#
# ⚠️ This runs once per ABI - the same as add_subdirectory did, since AGP invokes CMake per ABI.
include(ExternalProject)

set(LJT_SRC     ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo)
set(LJT_PREFIX  ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-ext)
set(LJT_INSTALL ${LJT_PREFIX}/install)
set(LJT_LIB     ${LJT_INSTALL}/lib/libturbojpeg.a)

# 🔴 Fail loudly HERE if the submodule was never fetched. Otherwise it surfaces much later as
#    "cannot find turbojpeg.h", which is far harder to trace back.
if(NOT EXISTS ${LJT_SRC}/CMakeLists.txt)
  message(FATAL_ERROR
    "libjpeg-turbo sources are missing. Fetch the submodule:\n"
    "    git submodule update --init --recursive")
endif()

ExternalProject_Add(libjpeg_turbo_ext
  SOURCE_DIR  ${LJT_SRC}
  PREFIX      ${LJT_PREFIX}
  INSTALL_DIR ${LJT_INSTALL}
  # 🔑 The outer build's toolchain is passed straight through; without it this builds for the host.
  CMAKE_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DANDROID_NDK=${ANDROID_NDK}
    -DCMAKE_ANDROID_ARCH_ABI=${CMAKE_ANDROID_ARCH_ABI}
    -DCMAKE_SYSTEM_NAME=${CMAKE_SYSTEM_NAME}
    -DCMAKE_SYSTEM_VERSION=${CMAKE_SYSTEM_VERSION}
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
    -DCMAKE_INSTALL_PREFIX=${LJT_INSTALL}
    -DCMAKE_INSTALL_LIBDIR=lib      # 🔑 lib64 would make LJT_LIB below point at nothing
    -DENABLE_SHARED=OFF
    -DENABLE_STATIC=ON
    -DWITH_TURBOJPEG=ON
    -DWITH_SIMD=ON
    -DREQUIRE_SIMD=ON
  # 🔑 Ninja has to know in advance what will be produced, or it cannot order the build.
  BUILD_BYPRODUCTS ${LJT_LIB}
)

# 🔑 This path does not exist at configure time; without creating it, CMake fails on a missing include directory.
file(MAKE_DIRECTORY ${LJT_INSTALL}/include)

add_library(turbojpeg-static STATIC IMPORTED)
set_target_properties(turbojpeg-static PROPERTIES IMPORTED_LOCATION ${LJT_LIB})

# ── Our JNI library ─────────────────────────────────────────────────────────
add_library(vnc_jni SHARED vnc_jni.cpp)
add_dependencies(vnc_jni libjpeg_turbo_ext)

target_include_directories(vnc_jni PRIVATE ${LJT_INSTALL}/include)

target_link_libraries(vnc_jni
        android
        log
        jnigraphics
        turbojpeg-static
)
