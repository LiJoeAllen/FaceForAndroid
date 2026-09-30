# =============================================================================
# SeetaFace6 + SeetaFace6JNI Android build orchestration
#
# Nothing inside third_party/ is patched. Each upstream module ships its own
# (desktop-oriented) CMakeLists.txt with forced install prefixes and install()
# side effects, so instead of add_subdirectory() we compile the exact source
# sets declared by the upstream android/jni/Android.mk files, in the order and
# with the link relationships those makefiles encode:
#
#   libORZ_static.a (OpenRoleZoo)
#     -> libSeetaAuthorize.so
#     -> libtennis.so (TenniS, CPU/NEON)
#         -> 11 SeetaFace6 feature shared libraries
#             -> 11 JNI shared libraries, named exactly as the Java side's
#                System.loadLibrary("...") calls expect.
# =============================================================================
cmake_minimum_required(VERSION 3.22)

project(seetaface6_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# face/sf/src/main/cpp -> face/sf
get_filename_component(SF_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
set(SEETA6_DIR "${SF_DIR}/third_party/SeetaFace6")
set(SEETA_JNI_DIR "${SF_DIR}/third_party/SeetaFace6JNI")

set(ORZ_DIR "${SEETA6_DIR}/OpenRoleZoo")
set(AUTHORIZE_DIR "${SEETA6_DIR}/SeetaAuthorize")
set(TENNIS_DIR "${SEETA6_DIR}/TenniS")

# ---------------------------------------------------------------------------
# Common compile settings, mirroring the upstream Application.mk flags:
# C++11, exceptions/RTTI on, permissive, hidden visibility.
# ---------------------------------------------------------------------------
add_library(seeta_compile_flags INTERFACE)
# -O2 even in debug builds: the 2019-era upstream kernels are not -O0 clean
# (large stack frames + UB exposed at -O0 -> SIGSEGV inside libtennis.so).
target_compile_options(seeta_compile_flags INTERFACE
    -O2
    -fexceptions
    -frtti
    -fpermissive
    -fvisibility=hidden
    -Wno-unused-command-line-argument
    "SHELL:-include \"${CMAKE_CURRENT_SOURCE_DIR}/cmake/seeta_compat.h\""
)
target_include_directories(seeta_compile_flags INTERFACE
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake"
)
# The upstream Android.mk adds -ffast-math/-ftree-vectorize/-funsafe-math-
# optimizations (tuned for NDK r16b). Under NDK 27 these break the TenniS
# kernels on-device (heap corruption -> SIGSEGV in depthwise conv / resize),
# so they are deliberately NOT reproduced here.

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(seeta_compile_flags INTERFACE
        -mfloat-abi=softfp
        -mfpu=neon-vfpv4
    )
endif()

# ---------------------------------------------------------------------------
# OpenRoleZoo — static library, source set copied verbatim from its
# android/jni/Android.mk (ssl module excluded upstream as well).
# ---------------------------------------------------------------------------
file(GLOB ORZ_SRCS CONFIGURE_DEPENDS
    "${ORZ_DIR}/src/*.cpp"
    "${ORZ_DIR}/src/orz/*.cpp"
    "${ORZ_DIR}/src/orz/codec/*.cpp"
    "${ORZ_DIR}/src/orz/io/*.cpp"
    "${ORZ_DIR}/src/orz/io/jug/*.cpp"
    "${ORZ_DIR}/src/orz/io/stream/*.cpp"
    "${ORZ_DIR}/src/orz/lego/*.cpp"
    "${ORZ_DIR}/src/orz/mem/*.cpp"
    "${ORZ_DIR}/src/orz/net/*.cpp"
    "${ORZ_DIR}/src/orz/sync/*.cpp"
    "${ORZ_DIR}/src/orz/tools/*.cpp"
    "${ORZ_DIR}/src/orz/utils/*.cpp"
)
add_library(seeta_orz STATIC ${ORZ_SRCS})
target_link_libraries(seeta_orz PUBLIC seeta_compile_flags)
target_include_directories(seeta_orz PUBLIC
    "${ORZ_DIR}"
    "${ORZ_DIR}/include"
)
# ORZ_PLATFORM_OS_ANDROID is defined in orz/utils/platform.h from __ANDROID__,
# which the NDK toolchain sets for us.

# ---------------------------------------------------------------------------
# SeetaAuthorize — shared library (model parsing / encryption).
# ---------------------------------------------------------------------------
file(GLOB AUTHORIZE_SRCS CONFIGURE_DEPENDS
    "${AUTHORIZE_DIR}/*.cpp"
    "${AUTHORIZE_DIR}/src/*.cpp"
)
add_library(SeetaAuthorize SHARED ${AUTHORIZE_SRCS})
target_link_libraries(SeetaAuthorize PRIVATE seeta_orz seeta_compile_flags log m)
target_include_directories(SeetaAuthorize PRIVATE
    "${AUTHORIZE_DIR}"
    "${AUTHORIZE_DIR}/include"
    "${AUTHORIZE_DIR}/include/hidden"
    "${AUTHORIZE_DIR}/include/lock"
)

# ---------------------------------------------------------------------------
# TenniS — forward computation engine, CPU build. Source set mirrors
# TenniS/android/jni/Android.mk. OpenMP is kept for the ARM targets, matching
# upstream; x86_64 builds without it because the NEON/ARM code paths are off.
# ---------------------------------------------------------------------------
file(GLOB TENNIS_SRCS CONFIGURE_DEPENDS
    "${TENNIS_DIR}/src/api/*.cpp"
    "${TENNIS_DIR}/src/backend/*.cpp"
    "${TENNIS_DIR}/src/backend/base/*.cpp"
    "${TENNIS_DIR}/src/backend/dragon/*.cpp"
    "${TENNIS_DIR}/src/backend/mxnet/*.cpp"
    "${TENNIS_DIR}/src/backend/onnx/*.cpp"
    "${TENNIS_DIR}/src/backend/tf/*.cpp"
    "${TENNIS_DIR}/src/backend/torch/*.cpp"
    "${TENNIS_DIR}/src/backend/zoo/*.cpp"
    "${TENNIS_DIR}/src/board/*.cpp"
    "${TENNIS_DIR}/src/compiler/*.cpp"
    "${TENNIS_DIR}/src/compiler/option/*.cpp"
    "${TENNIS_DIR}/src/core/*.cpp"
    "${TENNIS_DIR}/src/core/sync/*.cpp"
    "${TENNIS_DIR}/src/encryption/*.cpp"
    "${TENNIS_DIR}/src/engine/*.cpp"
    "${TENNIS_DIR}/src/frontend/*.cpp"
    "${TENNIS_DIR}/src/global/*.cpp"
    "${TENNIS_DIR}/src/kernels/common/*.cpp"
    "${TENNIS_DIR}/src/kernels/common/third/dragon/*.cpp"
    "${TENNIS_DIR}/src/kernels/cpu/*.cpp"
    "${TENNIS_DIR}/src/kernels/cpu/arm/*.cpp"
    "${TENNIS_DIR}/src/kernels/cpu/caffe/*.cpp"
    "${TENNIS_DIR}/src/kernels/cpu/dcn/*.cpp"
    "${TENNIS_DIR}/src/kernels/cpu/dragon/*.cpp"
    "${TENNIS_DIR}/src/kernels/cpu/quantized/*.cpp"
    "${TENNIS_DIR}/src/memory/*.cpp"
    "${TENNIS_DIR}/src/memory/orz/*.cpp"
    "${TENNIS_DIR}/src/module/*.cpp"
    "${TENNIS_DIR}/src/module/io/*.cpp"
    "${TENNIS_DIR}/src/runtime/*.cpp"
    "${TENNIS_DIR}/src/runtime/inferer/*.cpp"
    "${TENNIS_DIR}/src/runtime/inside/*.cpp"
    "${TENNIS_DIR}/src/runtime/instruction/*.cpp"
    "${TENNIS_DIR}/src/utils/*.cpp"
)
add_library(tennis SHARED ${TENNIS_SRCS})
target_link_libraries(tennis PRIVATE seeta_compile_flags log)
target_include_directories(tennis PUBLIC
    "${TENNIS_DIR}/include"
    "${TENNIS_DIR}/src"
)
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        target_compile_definitions(tennis PRIVATE TS_ON_ARMV7=ON)
    endif()
    # NEON/SIMD kernels disabled for now: under NDK 27 the NEON paths corrupt
    # the heap on-device (SIGSEGV in depthwise conv / resize); the generic C++
    # kernels are slower but stable. Re-enable after verifying with NDK r16b
    # or after patching upstream.
    target_compile_definitions(tennis PRIVATE
        TS_ON_ARM=ON TS_USE_OPENMP=ON
    )
    target_compile_options(tennis PRIVATE -fopenmp)
    # Dynamically link libomp.so (static -static-openmp segfaults on some
    # Android 16 devices). AGP packages IMPORTED targets referenced by a link.
    set(omp_clang_arch aarch64)
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(omp_clang_arch arm)
    endif()
    file(GLOB OMP_LIB
        "${ANDROID_NDK}/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/${omp_clang_arch}/libomp.so")
    list(LENGTH OMP_LIB OMP_LIB_COUNT)
    if(OMP_LIB_COUNT GREATER 0)
        list(GET OMP_LIB 0 OMP_LIB_PATH)
        add_library(omp_runtime SHARED IMPORTED GLOBAL)
        set_target_properties(omp_runtime PROPERTIES IMPORTED_LOCATION "${OMP_LIB_PATH}")
        target_link_libraries(tennis PRIVATE omp_runtime)
    else()
        message(WARNING "libomp.so not found in NDK clang runtime; libtennis.so will fail to load on-device")
    endif()
endif()

# ---------------------------------------------------------------------------
# Feature libraries. Each function call mirrors one upstream
# <Module>/android/jni/Android.mk: sources are seeta/*.cpp + src/seeta/*.cpp
# (+ src/seeta/plugin for QualityAssessor), SEETA_MODEL_ENCRYPT is defined,
# and the same prebuilt link relationships are expressed as real targets.
# ---------------------------------------------------------------------------
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/SeetaModules.cmake")

seeta_add_feature(SeetaFaceDetector600
    DIR "${SEETA6_DIR}/FaceBoxes/FaceDetector"
    LIBS tennis SeetaAuthorize)

seeta_add_feature(SeetaFaceRecognizer610
    DIR "${SEETA6_DIR}/FaceRecognizer6/FaceRecognizer"
    LIBS tennis SeetaAuthorize)

seeta_add_feature(SeetaFaceAntiSpoofingX600
    DIR "${SEETA6_DIR}/FaceAntiSpoofingX6/FaceAntiSpoofingX"
    LIBS tennis SeetaAuthorize)

# FaceTracking links the detector (its source includes seeta/FaceDetector.h);
# tennis/authorize come in transitively but are linked explicitly as well,
# matching their use in the tracker implementation.
seeta_add_feature(SeetaFaceTracking600
    DIR "${SEETA6_DIR}/FaceTracker6/FaceTracking"
    INCLUDES "${SEETA6_DIR}/FaceBoxes/FaceDetector/include"
    LIBS SeetaFaceDetector600 tennis SeetaAuthorize)

seeta_add_feature(SeetaFaceLandmarker600
    DIR "${SEETA6_DIR}/Landmarker/Landmarker"
    LIBS tennis SeetaAuthorize)

seeta_add_feature(SeetaPoseEstimation600
    DIR "${SEETA6_DIR}/PoseEstimator6/PoseEstimation"
    LIBS tennis SeetaAuthorize)

seeta_add_feature(SeetaQualityAssessor300
    DIR "${SEETA6_DIR}/QualityAssessor3/QualityAssessor"
    INCLUDES "${SEETA6_DIR}/PoseEstimator6/PoseEstimation/include"
    LIBS SeetaPoseEstimation600 tennis SeetaAuthorize)

seeta_add_feature(SeetaAgePredictor600
    DIR "${SEETA6_DIR}/SeetaAgePredictor/AgePredictor"
    LIBS tennis SeetaAuthorize)

seeta_add_feature(SeetaEyeStateDetector200
    DIR "${SEETA6_DIR}/SeetaEyeStateDetector/EyeStateDetector"
    LIBS tennis SeetaAuthorize)

seeta_add_feature(SeetaGenderPredictor600
    DIR "${SEETA6_DIR}/SeetaGenderPredictor/GenderPredictor"
    LIBS tennis SeetaAuthorize)

seeta_add_feature(SeetaMaskDetector200
    DIR "${SEETA6_DIR}/SeetaMaskDetector/MaskDetector"
    LIBS tennis SeetaAuthorize)

# ---------------------------------------------------------------------------
# JNI wrapper libraries. Output names must match the Java static blocks
# exactly (System.loadLibrary), including the two upstream spellings
# "FaceAntiSpoofing600_java" and "SeetaMaksDetector200_java".
# ---------------------------------------------------------------------------
seeta_add_jni(SeetaFaceDetector600_java
    JNI_DIR "${SEETA_JNI_DIR}/FaceBoxes"
    FEATURE_DIR "${SEETA6_DIR}/FaceBoxes/FaceDetector"
    FEATURE_LIB SeetaFaceDetector600
    SHIMS Common/CStruct.h Common/Struct.h src/seeta/FaceDetector.h
    SOURCES JNIFaceDetector.cpp)

seeta_add_jni(SeetaFaceRecognizer600_java
    JNI_DIR "${SEETA_JNI_DIR}/FaceRecognizer6"
    FEATURE_DIR "${SEETA6_DIR}/FaceRecognizer6/FaceRecognizer"
    FEATURE_LIB SeetaFaceRecognizer610
    SHIMS Common/CStruct.h
    SOURCES JNIFaceRecognizer.cpp JNIFaceDatabase.cpp)

seeta_add_jni(FaceAntiSpoofing600_java
    JNI_DIR "${SEETA_JNI_DIR}/FaceAntiSpoofingX6"
    FEATURE_DIR "${SEETA6_DIR}/FaceAntiSpoofingX6/FaceAntiSpoofingX"
    FEATURE_LIB SeetaFaceAntiSpoofingX600
    SHIMS Common/CStruct.h Common/Struct.h
    SOURCES JNIFaceAntiSpoofing.cpp)

seeta_add_jni(FaceTracker600_java
    JNI_DIR "${SEETA_JNI_DIR}/FaceTracker6"
    FEATURE_DIR "${SEETA6_DIR}/FaceTracker6/FaceTracking"
    FEATURE_LIB SeetaFaceTracking600
    PRE_INCLUDES "${SEETA6_DIR}/FaceTracker6/FaceTracking/include/seeta/CTrackingFaceInfo.h"
    SHIMS Common/CStruct.h Common/Struct.h
    SOURCES JNIFaceTracker.cpp)

seeta_add_jni(SeetaFaceLandmarker600_java
    JNI_DIR "${SEETA_JNI_DIR}/Landmarker"
    FEATURE_DIR "${SEETA6_DIR}/Landmarker/Landmarker"
    FEATURE_LIB SeetaFaceLandmarker600
    SHIMS Common/CStruct.h
    SOURCES JNIFaceLandmarker.cpp)

seeta_add_jni(PoseEstimation600_java
    JNI_DIR "${SEETA_JNI_DIR}/PoseEstimator6"
    FEATURE_DIR "${SEETA6_DIR}/PoseEstimator6/PoseEstimation"
    FEATURE_LIB SeetaPoseEstimation600
    SHIMS Common/CStruct.h
    SOURCES JNIPoseEstimator.cpp)

seeta_add_jni(QualityAssessor300_java
    JNI_DIR "${SEETA_JNI_DIR}/QualityAssessor3"
    FEATURE_DIR "${SEETA6_DIR}/QualityAssessor3/QualityAssessor"
    FEATURE_LIB SeetaQualityAssessor300
    SOURCES
        JNIQualityOfBrightness.cpp
        JNIQualityOfClarity.cpp
        JNIQualityOfIntegrity.cpp
        JNIQualityOfLBN.cpp
        JNIQualityOfPose.cpp
        JNIQualityOfPoseEx.cpp
        JNIQualityOfResolution.cpp)

seeta_add_jni(SeetaAgePredictor600_java
    JNI_DIR "${SEETA_JNI_DIR}/SeetaAgePredictor"
    FEATURE_DIR "${SEETA6_DIR}/SeetaAgePredictor/AgePredictor"
    FEATURE_LIB SeetaAgePredictor600
    SHIMS Common/CStruct.h
    SOURCES JNIAgePredictor.cpp)

seeta_add_jni(SeetaEyeStateDetector200_java
    JNI_DIR "${SEETA_JNI_DIR}/SeetaEyeStateDetector"
    FEATURE_DIR "${SEETA6_DIR}/SeetaEyeStateDetector/EyeStateDetector"
    FEATURE_LIB SeetaEyeStateDetector200
    SHIMS Common/CStruct.h
    SOURCES JNIEyeStateDetector.cpp)

seeta_add_jni(SeetaGenderPredictor600_java
    JNI_DIR "${SEETA_JNI_DIR}/SeetaGenderPredictor"
    FEATURE_DIR "${SEETA6_DIR}/SeetaGenderPredictor/GenderPredictor"
    FEATURE_LIB SeetaGenderPredictor600
    SHIMS Common/CStruct.h
    SOURCES JNIGenderPredictor.cpp)

# Upstream typo "Maks" preserved on purpose: the Java class loads exactly
# this name, so the .so must be identical.
seeta_add_jni(SeetaMaksDetector200_java
    JNI_DIR "${SEETA_JNI_DIR}/SeetaMaskDetector"
    FEATURE_DIR "${SEETA6_DIR}/SeetaMaskDetector/MaskDetector"
    FEATURE_LIB SeetaMaskDetector200
    SHIMS Common/CStruct.h
    SOURCES JNIMaskDetector.cpp)

# ---------------------------------------------------------------------------
# JNI name bridges (module-level adaptations, third_party stays untouched):
# upstream symbol mismatches that otherwise fail at runtime with
# UnsatisfiedLinkError. See bridges/*.cpp for the specifics.
# ---------------------------------------------------------------------------
target_sources(QualityAssessor300_java PRIVATE
    bridges/QualityOfBirghtnessBridge.cpp)
target_sources(SeetaFaceDetector600_java PRIVATE
    bridges/FaceDetectorAccessorBridge.cpp)
