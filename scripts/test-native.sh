#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/native-tests
"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -g -pthread \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Iapp/src/main/cpp/include tests/native/core_test.cpp \
  app/src/main/cpp/src/memory_pool.cpp -o build/native-tests/core_test
build/native-tests/core_test
"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -g -fsanitize=address,undefined \
  -fno-omit-frame-pointer -Iapp/src/main/cpp/include tests/native/dng_preview_test.cpp \
  app/src/main/cpp/src/dng_preview.cpp -o build/native-tests/dng_preview_test
build/native-tests/dng_preview_test
"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -g -fsanitize=address,undefined \
  -fno-omit-frame-pointer -Iapp/src/main/cpp/include tests/native/ccraw_preview_test.cpp \
  app/src/main/cpp/src/{ccraw_preview,ccraw_writer,crc32_dispatch,dng_preview}.cpp -o build/native-tests/ccraw_preview_test
build/native-tests/ccraw_preview_test
printf 'Native core tests passed.\n'
crc_flags=()
crc_objects=()
if [[ "$(uname -m)" == "aarch64" ]]; then
  "${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -g -fsanitize=address,undefined \
    -fno-omit-frame-pointer -march=armv8-a+crc -fno-lto \
    -c app/src/main/cpp/src/crc32_arm64.cpp -o build/native-tests/crc32_arm64.o
  crc_flags=(-DCINECAM_ARM64_CRC=1)
  crc_objects=(build/native-tests/crc32_arm64.o)
fi
modes=(selected forced-portable)
if [[ "$(uname -m)" == "aarch64" ]]; then modes+=(self-test-failure); fi
for mode in "${modes[@]}"; do
  flags=("${crc_flags[@]}")
  if [[ "$mode" == "forced-portable" ]]; then flags+=(-DCINECAM_FORCE_PORTABLE_CRC=1); fi
  if [[ "$mode" == "self-test-failure" ]]; then flags+=(-DCINECAM_CRC_TEST_FAIL_SELFTEST=1); fi
  "${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -g -pthread \
    -fsanitize=address,undefined -fno-omit-frame-pointer "${flags[@]}" \
    -Iapp/src/main/cpp/include tests/native/pipeline_test.cpp \
    app/src/main/cpp/src/{memory_pool,ring_buffer,cine_raw_engine,raw_writer,ccraw_writer,crc32_dispatch,raw16_codec,bp16_archive,export_copy,cinemadng_writer,sync_timeline}.cpp \
    "${crc_objects[@]}" -o build/native-tests/pipeline_test
  # Expanded sanitizer suites need headroom on shared ARM runners; still bounded.
  timeout 60s build/native-tests/pipeline_test
  printf 'Native queue, lifecycle, writer, sync and CRC tests passed (%s).\n' "$mode"
  python3 tests/native/test_ccraw_reader.py
  python3 tests/native/test_bp16_archive.py
done
