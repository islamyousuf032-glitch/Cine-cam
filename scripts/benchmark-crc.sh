#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/native-tests
"${CXX:-g++}" -std=c++17 -O3 -Wall -Wextra -Werror \
  -Iapp/src/main/cpp/include tests/native/crc_benchmark.cpp \
  app/src/main/cpp/src/{ccraw_writer,crc32_dispatch}.cpp -o build/native-tests/crc_benchmark
build/native-tests/crc_benchmark
