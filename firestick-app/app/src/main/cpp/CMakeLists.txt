cmake_minimum_required(VERSION 3.22.1)
project(castviewer_airplay C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)

include(FetchContent)

# Keep downloaded dependencies inside each ABI build tree to avoid multi-ABI races and keep paths short on Windows
set(FETCHCONTENT_BASE_DIR "${CMAKE_BINARY_DIR}/_d" CACHE PATH "" FORCE)

# ---------- 1. OpenSSL 1.1.1w (pure CMake via janbar/openssl-cmake, no Perl/autotools needed) ----------
set(WITH_APPS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    openssl
    GIT_REPOSITORY https://github.com/janbar/openssl-cmake.git
    GIT_TAG b0ac69581958cd658364147da9057da89a01c394
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(openssl)

# ---------- 2. libplist 2.6.0 (pure C static library) ----------
if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)
endif()
FetchContent_Declare(
    libplist
    GIT_REPOSITORY https://github.com/libimobiledevice/libplist.git
    GIT_TAG 2.6.0
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(libplist)
if(NOT libplist_POPULATED)
    FetchContent_Populate(libplist)
endif()

set(PLIST_SRC "${libplist_SOURCE_DIR}/src")
set(PLIST_CNARY "${libplist_SOURCE_DIR}/libcnary")

add_library(plist STATIC
    ${PLIST_SRC}/base64.c
    ${PLIST_SRC}/bplist.c
    ${PLIST_SRC}/bytearray.c
    ${PLIST_SRC}/hashtable.c
    ${PLIST_SRC}/jplist.c
    ${PLIST_SRC}/jsmn.c
    ${PLIST_SRC}/oplist.c
    ${PLIST_SRC}/out-default.c
    ${PLIST_SRC}/out-limd.c
    ${PLIST_SRC}/out-plutil.c
    ${PLIST_SRC}/plist.c
    ${PLIST_SRC}/ptrarray.c
    ${PLIST_SRC}/time64.c
    ${PLIST_SRC}/xplist.c
    ${PLIST_CNARY}/cnary.c
    ${PLIST_CNARY}/node.c
    ${PLIST_CNARY}/node_list.c
)
target_include_directories(plist
    PUBLIC "${libplist_SOURCE_DIR}/include"
    PRIVATE "${PLIST_SRC}" "${PLIST_CNARY}/include"
)
target_compile_definitions(plist PRIVATE
    _GNU_SOURCE
    HAVE_STRNDUP=1
    PACKAGE_VERSION="2.6.0"
)

# ---------- 3. Vendored UxPlay sub-libraries + MIT ALAC decoder ----------
set(UXPLAY_LIB "${CMAKE_CURRENT_SOURCE_DIR}/third_party/uxplay/lib")

add_library(llhttp STATIC
    ${UXPLAY_LIB}/llhttp/api.c
    ${UXPLAY_LIB}/llhttp/http.c
    ${UXPLAY_LIB}/llhttp/llhttp.c
)
target_include_directories(llhttp PUBLIC "${UXPLAY_LIB}/llhttp")

add_library(playfair STATIC
    ${UXPLAY_LIB}/playfair/hand_garble.c
    ${UXPLAY_LIB}/playfair/modified_md5.c
    ${UXPLAY_LIB}/playfair/omg_hax.c
    ${UXPLAY_LIB}/playfair/playfair.c
    ${UXPLAY_LIB}/playfair/sap_hash.c
)
target_include_directories(playfair PUBLIC "${UXPLAY_LIB}/playfair")

add_library(alac STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/alac/alac.c
)
target_include_directories(alac PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/third_party/alac")

set(UXPLAY_CORE_SRCS
    ${UXPLAY_LIB}/airplay_video.c
    ${UXPLAY_LIB}/byteutils.c
    ${UXPLAY_LIB}/compat.c
    ${UXPLAY_LIB}/crypto.c
    ${UXPLAY_LIB}/fairplay_playfair.c
    ${UXPLAY_LIB}/http_request.c
    ${UXPLAY_LIB}/http_response.c
    ${UXPLAY_LIB}/httpd.c
    ${UXPLAY_LIB}/logger.c
    ${UXPLAY_LIB}/mirror_buffer.c
    ${UXPLAY_LIB}/netutils.c
    ${UXPLAY_LIB}/pairing.c
    ${UXPLAY_LIB}/raop.c
    ${UXPLAY_LIB}/raop_buffer.c
    ${UXPLAY_LIB}/raop_ntp.c
    ${UXPLAY_LIB}/raop_rtp.c
    ${UXPLAY_LIB}/raop_rtp_mirror.c
    ${UXPLAY_LIB}/srp.c
    ${UXPLAY_LIB}/utils.c
)

add_library(castviewer_airplay SHARED
    ${UXPLAY_CORE_SRCS}
    ${CMAKE_CURRENT_SOURCE_DIR}/android_dnssd_shim.c
    ${CMAKE_CURRENT_SOURCE_DIR}/airplay_jni.cpp
)

target_include_directories(castviewer_airplay PRIVATE
    "${UXPLAY_LIB}"
    "${UXPLAY_LIB}/llhttp"
    "${UXPLAY_LIB}/playfair"
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/alac"
    "${openssl_SOURCE_DIR}/include"
    "${openssl_BINARY_DIR}/include"
)

target_compile_definitions(castviewer_airplay PRIVATE
    ANDROID
    __STDC_CONSTANT_MACROS
    __STDC_LIMIT_MACROS
    NOHOLD
    PLIST_210
    PLIST_230
    OPENSSL_API_COMPAT=0x10101000L
)

target_compile_options(castviewer_airplay PRIVATE
    -O2
    -fPIC
    -Wall
    -Wno-unused-function
    -Wno-unused-variable
)

target_link_libraries(castviewer_airplay
    llhttp
    playfair
    alac
    plist
    crypto
    log
    android
    m
)
