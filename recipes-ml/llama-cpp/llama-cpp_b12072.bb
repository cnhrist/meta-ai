SUMMARY = "Fast LLM inference in C/C++"
DESCRIPTION = "llama.cpp provides fast, CPU-first inference for large language models in pure C/C++ with optional GPU acceleration backends."
HOMEPAGE = "https://github.com/ggml-org/llama.cpp"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://LICENSE;md5=223b26b3c1143120c87e2b13111d3e99"

SRCREV = "ca8daad199a9c12c5b54643b974c640939a0f7bd"
SRC_URI = "git://github.com/ggml-org/llama.cpp.git;protocol=https;tag=${PV};nobranch=1"

inherit cmake pkgconfig

# Optional HTTPS support in llama-server.
PACKAGECONFIG ?= ""
PACKAGECONFIG[openssl] = "-DLLAMA_OPENSSL=ON,-DLLAMA_OPENSSL=OFF,openssl,"

PACKAGECONFIG[hexagon] = "-DGGML_HEXAGON=ON,-DGGML_HEXAGON=OFF"

# Hexagon DSP backend requires python3 at configure time, and an
# externally-provided Hexagon SDK (HEXAGON_SDK_ROOT) with its own
# compiler toolchain; HEXAGON_TOOLS_ROOT is auto-derived by ggml
# from HEXAGON_SDK_ROOT/hexagon_sdk.json, so it is not set here.
DEPENDS += "${@bb.utils.contains('PACKAGECONFIG', 'hexagon', 'python3-native', '', d)}"
HEXAGON_SDK_ROOT ??= ""

EXTRA_OECMAKE += " \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_NATIVE=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_WEBUI=OFF \
    -DLLAMA_TOOLS_INSTALL=ON \
"

# hexagon_fun.cmake (from the Hexagon SDK) is include()'d directly by
# ggml-hexagon/CMakeLists.txt at the top level, before PREBUILT_LIB_DIR is
# ever set. Several unguarded 'string(FIND ${PREBUILT_LIB_DIR} ...)' calls
# in that file then see an empty/undefined variable vanish from the
# argument list entirely (CMake's unquoted-empty-variable behavior),
# erroring with 'string sub-command FIND requires 3 or 4 parameters'. The
# code that actually uses those results is guarded by
# CMAKE_SYSTEM_NAME MATCHES "QURT", which never applies to this
# host-side aarch64 build, so any non-empty placeholder value is safe.
EXTRA_OECMAKE += "${@bb.utils.contains('PACKAGECONFIG', 'hexagon', '-DHEXAGON_SDK_ROOT=' + d.getVar('HEXAGON_SDK_ROOT') + ' -DPREBUILT_LIB_DIR=none', '', d)}"

# The Hexagon HTP skel sub-builds (ggml-hexagon/CMakeLists.txt's
# ExternalProject_Add calls, one per DSP arch) run their own fresh cmake
# configure during do_compile (BUILD_ALWAYS 1), using the hexagon-clang
# cross toolchain. They inherit this task's environment, and neither
# hexagon_fun.cmake nor ggml-hexagon's CMakeLists.txt override
# CMAKE_C_FLAGS/CMAKE_CXX_FLAGS, so CMake seeds them from the ambient
# CFLAGS/CXXFLAGS env vars -- which here are OE's aarch64-target
# reproducible-build flags (e.g. -fcanon-prefix-map) that hexagon-clang
# doesn't understand. Clearing them for do_compile only affects these
# fresh nested configures; the outer aarch64 build's flags are already
# baked into build.ninja from do_configure and are unaffected.
do_compile:prepend() {
    if ${@bb.utils.contains('PACKAGECONFIG', 'hexagon', 'true', 'false', d)}; then
        unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS
    fi
}

# This Hexagon SDK/Tools release (6.6.0.0 / Tools 19.0.07) has no 'v85'
# entry in build/cmake/hexagon_arch.cmake's arch tables (they stop at
# v81); any build_htp_skel(v85) sub-build silently falls back to v68 at
# the toolchain-selection level (confirmed: its compile commands use
# -mcpu=v68/-mhvx=v68 and rtos/qurt/computev68 includes despite being
# the v85 skel), which then fails to compile because ggml-hexagon's HVX
# intrinsics are guarded by '#if __HVX_ARCH__ >= 73'. The target board
# only needs v73 (v73/v75/v79/v81 all build and link fine), so drop the
# v85 skel rather than requiring a newer SDK release just for an unused
# arch.
do_configure:prepend() {
    if ${@bb.utils.contains('PACKAGECONFIG', 'hexagon', 'true', 'false', d)}; then
        sed -i '/^build_htp_skel(v85)/d' ${S}/ggml/src/ggml-hexagon/CMakeLists.txt
    fi
}

# The Hexagon HTP skel .so files are Hexagon-ISA ELF binaries, not
# aarch64, so OE's target strip/objcopy tools (aarch64-qcom-linux-strip,
# -objcopy) fail with 'Unable to recognise the architecture of the
# input file' when they try to strip/debug-split them -- both during
# do_populate_sysroot (oe.package.strip_execs has no per-file exemption,
# so inhibit sysroot stripping for this recipe entirely; the sysroot
# copy is only used for cross-compiling other recipes against it, not
# shipped to target, so leaving it unstripped there has no size impact)
# and during do_package (oe.package.process_split_and_strip_files does
# support per-file exemption via INHIBIT_PACKAGE_STRIP_FILES, matched
# as absolute paths under ${PKGD}).
INHIBIT_SYSROOT_STRIP = "${@bb.utils.contains('PACKAGECONFIG', 'hexagon', '1', '0', d)}"

INHIBIT_PACKAGE_STRIP_FILES += "${@bb.utils.contains('PACKAGECONFIG', 'hexagon', ' '.join(d.getVar('PKGD') + d.getVar('libdir') + '/libggml-htp-' + v + '.so' for v in ('v73', 'v75', 'v79', 'v81')), '', d)}"

# Newer llama.cpp (>= b1xxxx) splits each CLI tool into a thin executable
# plus a shared '*-impl.so' helper library; those have no SONAME version
# suffix, so OE's default rules would place them (incorrectly, as real
# code) into -dev and make the runtime package depend on -dev for them.
# Keep them in the runtime package instead.
PACKAGES = "${PN}-src ${PN}-dbg ${PN}-staticdev ${PN}-htp ${PN} ${PN}-dev ${PN}-doc ${PN}-locale"
FILES:${PN} += "${libdir}/libllama-*-impl.so"

# The Hexagon HTP skel .so files (libggml-htp-v73.so etc.) are
# Hexagon-ISA ELF binaries loaded at runtime by the Hexagon DSP
# tools, not aarch64 shared libraries -- they fail essentially every
# ELF-oriented QA check OE runs for a normal target .so (arch, missing
# GNU_HASH/ldflags, embedded TMPDIR build paths, 'requires libc.so'
# with no RDEPENDS provider, and defaulting into -dev for being an
# unversioned .so). None of that QA is meaningful for them, so give
# them their own package (ordered before ${PN}/${PN}-dev in PACKAGES
# so it claims these files first) and skip those checks only there,
# leaving QA intact for the rest of the recipe's real aarch64 output.
FILES:${PN}-htp = "${libdir}/libggml-htp-*.so"
INSANE_SKIP:${PN}-htp = "arch buildpaths dev-elf ldflags file-rdeps"
RDEPENDS:${PN} += "${@bb.utils.contains('PACKAGECONFIG', 'hexagon', '${PN}-htp', '', d)}"

