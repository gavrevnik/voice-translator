#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
repo_dir="$(cd "$android_dir/.." && pwd)"
whisper_dir="${WHISPER_CPP_DIR:-$repo_dir/references/whisper.cpp}"
source_model="${1:-$android_dir/optional/whisper/models/ggml-large-v3-turbo.bin}"
output_model="${2:-$android_dir/model-packs/ggml-large-v3-turbo-q4_0.bin}"
quantization="${3:-q4_0}"
build_dir="${TMPDIR:-/tmp}/sayit-whisper-quantize"

if [[ ! -f "$whisper_dir/CMakeLists.txt" ]]; then
  echo "whisper.cpp source is missing: $whisper_dir" >&2
  echo "Checkout commit da54572229bcf64ba367d96c7ef15770376c4280 there first." >&2
  exit 1
fi

if [[ ! -f "$source_model" ]]; then
  echo "Source Whisper model is missing: $source_model" >&2
  exit 1
fi

mkdir -p "$(dirname "$output_model")"
cmake -S "$whisper_dir" -B "$build_dir" \
  -DWHISPER_BUILD_TESTS=OFF \
  -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_BUILD_SERVER=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_CCACHE=OFF
cmake --build "$build_dir" --target whisper-quantize --config Release -j 4
"$build_dir/bin/whisper-quantize" "$source_model" "$output_model" "$quantization"

echo "Created $output_model"
shasum -a 256 "$output_model"
