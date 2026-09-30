# Build helpers for SeetaFace6 feature libraries and their JNI wrappers.
# Included by CMakeLists.txt; relies on the variables/targets it sets there
# (SEETA6_DIR, TENNIS_DIR, AUTHORIZE_DIR, ORZ_DIR, seeta_orz,
# seeta_compile_flags).

# -----------------------------------------------------------------------------
# seeta_add_feature(<name> DIR <module dir> [INCLUDES ...] [LIBS ...])
#
# Compiles one SeetaFace6 module shared library from the exact source set its
# android/jni/Android.mk declares: <dir>/seeta/*.cpp (vendored graphics
# helpers) and <dir>/src/**.cpp (recursive, covers src/seeta/plugin used by
# QualityAssessor), with SEETA_MODEL_ENCRYPT defined.
# -----------------------------------------------------------------------------
function(seeta_add_feature outname)
    cmake_parse_arguments(FEA "" "DIR" "INCLUDES;LIBS" ${ARGN})

    file(GLOB FEA_SEETA_SRCS CONFIGURE_DEPENDS "${FEA_DIR}/seeta/*.cpp")
    file(GLOB_RECURSE FEA_SRC_SRCS CONFIGURE_DEPENDS "${FEA_DIR}/src/*.cpp")

    add_library(${outname} SHARED ${FEA_SEETA_SRCS} ${FEA_SRC_SRCS})
    target_include_directories(${outname} PRIVATE
        "${FEA_DIR}"
        "${FEA_DIR}/include"
        "${TENNIS_DIR}/include"
        "${AUTHORIZE_DIR}/include"
        "${AUTHORIZE_DIR}/include/hidden"
        "${ORZ_DIR}/include"
        "${ORZ_DIR}"
        ${FEA_INCLUDES}
    )
    target_compile_definitions(${outname} PRIVATE SEETA_MODEL_ENCRYPT)
    target_link_libraries(${outname} PRIVATE
        seeta_orz
        seeta_compile_flags
        log
        ${FEA_LIBS}
    )
endfunction()

# Compatibility shim body: SeetaFace6JNI was written against a newer API where
# ModelSetting.device is a string and the class is named ModelSetting_v2. The
# pinned SeetaFace6 modules only have the enum-based seeta::ModelSetting, so we
# provide an implicitly-convertible wrapper with the call surface the JNI code
# uses (string ctors, append, device/id fields).
set(SEETA_V2_COMPAT_CONTENT [=[
#pragma once

#include "seeta/Common/Struct.h"

#include <cctype>
#include <string>
#include <vector>

namespace seeta {

class ModelSetting_v2
{
public:
    // Mirrors the newer upstream API: device is a free-form string such as
    // "cpu"/"gpu"/"auto"; it is translated to the enum at NativeClass
    // construction time.
    const char *device = nullptr;
    int id = 0;

    ModelSetting_v2() = default;

    ModelSetting_v2(const std::string &model, const std::string &dev, int dev_id)
    {
        m_models.push_back(model);
        id = dev_id;
        set_device(dev);
    }

    ModelSetting_v2(const char *model, const char *dev, int dev_id)
    {
        if (model) m_models.emplace_back(model);
        id = dev_id;
        set_device(dev ? dev : "");
    }

    ModelSetting_v2(const std::vector<std::string> &models, const std::string &dev, int dev_id)
        : m_models(models), id(dev_id)
    {
        set_device(dev);
    }

    void append(const std::string &model) { m_models.push_back(model); }

    void append(const std::vector<std::string> &models)
    {
        m_models.insert(m_models.end(), models.begin(), models.end());
    }

    operator ModelSetting() const
    {
        return ModelSetting(m_models, parse_device(device), id);
    }

private:
    std::vector<std::string> m_models;
    std::string m_device_storage;

    void set_device(const std::string &dev)
    {
        m_device_storage = dev;
        device = m_device_storage.c_str();
    }

    static ModelSetting::Device parse_device(const char *dev)
    {
        std::string text = dev ? dev : "";
        for (char &ch : text) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
        if (text.find("gpu") != std::string::npos) return ModelSetting::GPU;
        if (text.find("cpu") != std::string::npos) return ModelSetting::CPU;
        return ModelSetting::AUTO;
    }
};

} // namespace seeta
]=])

# -----------------------------------------------------------------------------
# seeta_add_jni(<name> JNI_DIR <dir> FEATURE_DIR <dir> FEATURE_LIB <lib>
#               [PRE_INCLUDES <abs headers...>]
#               [SHIMS header...] SOURCES cpp...)
#
# Builds one JNI wrapper shared library. SHIMS lists upstream include paths
# that do not exist verbatim in this checkout; one-line forwarding headers
# are generated under the build directory instead of patching the submodule:
#   Common/X.h                -> seeta/Common/X.h
#   src/seeta/FaceDetector.h  -> seeta/FaceDetector.h
# (The upstream "seeta/cTrackingFaceInfo.h" spelling is handled via
# PRE_INCLUDES with the real CTrackingFaceInfo.h: a forwarding file would
# include itself on case-insensitive Windows filesystems.)
# -----------------------------------------------------------------------------
function(seeta_add_jni outname)
    cmake_parse_arguments(JNI
        "" "JNI_DIR;FEATURE_DIR;FEATURE_LIB" "PRE_INCLUDES;SHIMS;SOURCES" ${ARGN})

    set(shim_dir "${CMAKE_BINARY_DIR}/shims/${outname}")
    file(MAKE_DIRECTORY "${shim_dir}")

    # ModelSetting_v2 adapter, force-included before every translation unit.
    set(v2_header "${shim_dir}/seeta_v2_compat.h")
    file(WRITE "${v2_header}" "${SEETA_V2_COMPAT_CONTENT}")

    foreach(shim IN LISTS JNI_SHIMS)
        if(shim MATCHES "^Common/")
            string(REPLACE "Common/" "seeta/Common/" target_header "${shim}")
        elseif(shim STREQUAL "src/seeta/FaceDetector.h")
            set(target_header "seeta/FaceDetector.h")
        else()
            message(FATAL_ERROR "No shim mapping defined for '${shim}'")
        endif()

        get_filename_component(shim_parent "${shim_dir}/${shim}" DIRECTORY)
        file(MAKE_DIRECTORY "${shim_parent}")
        file(WRITE "${shim_dir}/${shim}"
            "// Generated shim for the unmodified SeetaFace6JNI sources.\n"
            "#pragma once\n"
            "#include \"${target_header}\"\n")
    endforeach()

    set(jni_sources "")
    foreach(src IN LISTS JNI_SOURCES)
        list(APPEND jni_sources "${JNI_JNI_DIR}/${src}")
    endforeach()

    add_library(${outname} SHARED ${jni_sources})
    target_include_directories(${outname} PRIVATE
        "${shim_dir}"
        "${JNI_JNI_DIR}"
        "${JNI_FEATURE_DIR}/include"
        "${TENNIS_DIR}/include"
        "${AUTHORIZE_DIR}/include"
        "${ORZ_DIR}/include"
    )

    set(force_includes "${v2_header}")
    foreach(pre IN LISTS JNI_PRE_INCLUDES)
        list(APPEND force_includes "${pre}")
    endforeach()
    foreach(inc IN LISTS force_includes)
        target_compile_options(${outname} PRIVATE "SHELL:-include \"${inc}\"")
    endforeach()

    # ORZ is linked statically into the feature libraries with hidden
    # visibility, so the JNI layer carries its own copy for the orz::need
    # helpers used by jni_struct.hpp.
    target_link_libraries(${outname} PRIVATE
        ${JNI_FEATURE_LIB}
        seeta_orz
        seeta_compile_flags
        log
    )
endfunction()
