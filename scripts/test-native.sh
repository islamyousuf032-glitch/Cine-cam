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
"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -g -pthread \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Iapp/src/main/cpp/include tests/native/pipeline_test.cpp \
  app/src/main/cpp/src/{memory_pool,ring_buffer,cine_raw_engine,raw_writer,ccraw_writer,cinemadng_writer,sync_timeline}.cpp \
  -o build/native-tests/pipeline_test
timeout 30s build/native-tests/pipeline_test
printf 'Native queue, engine lifecycle, writer failure and sync timeline tests passed.\n'
python3 tests/native/test_ccraw_reader.py
