#!/usr/bin/env python3
"""Build the full-precision OPUS Indo-European pack consumed by Android."""

from __future__ import annotations

import build_offline_opus_pack as builder


builder.SOURCE_URL = (
    "https://object.pouta.csc.fi/Tatoeba-MT-models/ine-ine/"
    "opus-2020-07-27.zip"
)
builder.SOURCE_SHA256 = (
    "3680ab7bd5f840af3c6bcf669f98463a9746c8c15987af646a89851fe0f6927a"
)
builder.SOURCE_ARCHIVE_NAME = "opus-2020-07-27.zip"
builder.SOURCE_DISPLAY_NAME = "Helsinki-NLP/opus-mt-ine-ine (opus-2020-07-27)"
builder.MODEL_ID = "opus-mt-ine-ine-fp32"
builder.MODEL_VERSION = "1"
builder.DEFAULT_DOWNLOAD_URL = (
    "https://github.com/gavrevnik/voice-translator/releases/download/"
    "offline-opus-ine-fp32-v1/offline-opus-ine-fp32-v1.zip"
)
builder.PACK_NAME = "offline-opus-ine-fp32-v1.zip"
builder.DISPLAY_NAME = "OPUS Indo-European FP32 — Offline"
builder.LICENSE_OUTPUT_NAME = "LICENSE.opus-mt-ine-ine"
builder.SUPPORTED_DIRECTIONS = ["ru-ro", "ro-ru", "ru-es", "es-ru"]
builder.SUPPORTED_SCRIPTS = ["rus", "ron", "spa"]
builder.DEFAULT_SERBIAN_SCRIPT = ""


if __name__ == "__main__":
    builder.main()
