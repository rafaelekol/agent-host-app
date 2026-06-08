cmake_minimum_required(VERSION 3.22.1)

project("agenthost")

# Prebuilt nodejs-mobile bundle, expected at app/libnode (relative to this
# file at app/src/main/cpp). Download it from:
#   https://github.com/nodejs-mobile/nodejs-mobile/releases
set(LIBNODE_DIR ${CMAKE_SOURCE_DIR}/../../../libnode)

# Our thin JNI bridge that calls node::Start.
add_library(native-lib SHARED native-lib.cpp)

# Import the prebuilt Node.js shared library for the current ABI.
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
        ${LIBNODE_DIR}/bin/${ANDROID_ABI}/libnode.so)

include_directories(${LIBNODE_DIR}/include/node)

find_library(log-lib log)

target_link_libraries(native-lib libnode ${log-lib})
