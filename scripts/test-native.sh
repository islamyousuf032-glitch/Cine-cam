#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/native-tests
"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -g -pthread \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Iapp/src/main/cpp/include tests/native/core_test.cpp \
  app/src/main/cpp/src/memory_pool.cpp -o build/native-tests/core_test
build/native-tests/core_test
printf 'Native core tests passed.\n'
