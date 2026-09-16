#!/usr/bin/env python3
"""Build a full-precision OPUS model pack consumed by the Android app.

The script intentionally uses only the Python standard library. It downloads
the pinned OPUS archive when needed, aligns its SentencePiece model IDs with
the Marian vocabulary, creates an FP32 model and lexical shortlist, and writes
a deterministic ZIP plus its delivery metadata.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path


SOURCE_URL = (
    "https://object.pouta.csc.fi/Tatoeba-MT-models/sla-sla/"
    "opus-2020-07-27.zip"
)
SOURCE_SHA256 = "b6cf76509de6ee34789ec2d169f4c5f85bd25170deb2ab75f2896c0f1b347a17"
SOURCE_ARCHIVE_NAME = "opus-2020-07-27.zip"
SOURCE_DISPLAY_NAME = "Helsinki-NLP/opus-mt-sla-sla (opus-2020-07-27)"
MODEL_ID = "opus-mt-sla-sla-fp32"
MODEL_VERSION = "1"
RUNTIME_VERSION = "translate-kit-android 0.1.0"
DEFAULT_DOWNLOAD_URL = (
    "https://github.com/gavrevnik/voice-translator/releases/download/"
    "offline-opus-sla-fp32-v1/offline-opus-sla-fp32-v1.zip"
)
ORIGINAL_MODEL = "opus.spm32k-spm32k.transformer.model1.npz.best-perplexity.npz"
ORIGINAL_VOCAB = "opus.spm32k-spm32k.vocab.yml"
MODEL_FILE = "model.float32.bin"
GEMM_TYPE = "float32"
PRECISION_NAME = "FP32"
PACK_NAME = "offline-opus-sla-fp32-v1.zip"
DISPLAY_NAME = "OPUS Slavic FP32 — Offline"
LICENSE_OUTPUT_NAME = "LICENSE.opus-mt-sla-sla"
SUPPORTED_DIRECTIONS = ["ru-sr", "sr-ru", "ru-hr", "hr-ru"]
SUPPORTED_SCRIPTS = ["srp_Latn", "srp_Cyrl", "hrv"]
DEFAULT_SERBIAN_SCRIPT = "srp_Latn"
SHORTLIST_FREQUENT_TOKENS = 16_000


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7


def encode_varint(value: int) -> bytes:
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def protobuf_fields(data: bytes):
    offset = 0
    while offset < len(data):
        start = offset
        tag, offset = read_varint(data, offset)
        wire_type = tag & 7
        number = tag >> 3
        value = None
        if wire_type == 0:
            _, offset = read_varint(data, offset)
        elif wire_type == 1:
            offset += 8
        elif wire_type == 2:
            length, offset = read_varint(data, offset)
            value_start = offset
            offset += length
            value = data[value_start:offset]
        elif wire_type == 5:
            offset += 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire_type}")
        yield number, wire_type, value, data[start:offset]


def length_field(number: int, value: bytes) -> bytes:
    return encode_varint((number << 3) | 2) + encode_varint(len(value)) + value


def varint_field(number: int, value: int) -> bytes:
    return encode_varint(number << 3) + encode_varint(value)


def parse_piece(data: bytes) -> tuple[str, float, int]:
    token = ""
    score = -30.0
    piece_type = 1
    for number, wire_type, value, raw in protobuf_fields(data):
        if number == 1 and wire_type == 2 and value is not None:
            token = value.decode("utf-8")
        elif number == 2 and wire_type == 5:
            score = struct.unpack("<f", raw[-4:])[0]
        elif number == 3 and wire_type == 0:
            piece_type, _ = read_varint(raw, 1)
    return token, score, piece_type


def encode_piece(token: str, score: float, piece_type: int) -> bytes:
    return (
        length_field(1, token.encode("utf-8"))
        + encode_varint((2 << 3) | 5)
        + struct.pack("<f", score)
        + varint_field(3, piece_type)
    )


def patch_trainer_spec(data: bytes, vocab_size: int) -> bytes:
    replaced_fields = {4, 40, 41, 42, 43, 45, 46, 47, 48}
    fields = [raw for number, _, _, raw in protobuf_fields(data) if number not in replaced_fields]
    fields.extend(
        [
            varint_field(4, vocab_size),
            varint_field(40, 1),  # <unk>
            varint_field(41, (1 << 64) - 1),  # no BOS
            varint_field(42, 0),  # </s>
            varint_field(43, (1 << 64) - 1),  # no PAD
            length_field(45, b"<unk>"),
            length_field(47, b"</s>"),
        ],
    )
    return b"".join(fields)


def load_sentencepiece(path: Path):
    pieces = {}
    trainer_spec = None
    other_fields = []
    for number, wire_type, value, raw in protobuf_fields(path.read_bytes()):
        if number == 1 and wire_type == 2 and value is not None:
            piece = parse_piece(value)
            pieces[piece[0]] = piece
        elif number == 2 and wire_type == 2:
            trainer_spec = value
        else:
            other_fields.append(raw)
    if trainer_spec is None:
        raise ValueError(f"{path} has no SentencePiece TrainerSpec")
    return pieces, trainer_spec, other_fields


def load_marian_vocab(path: Path) -> list[str]:
    indexed = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, raw_index = line.rsplit(": ", 1)
        token = parse_yaml_scalar(key)
        indexed[int(raw_index)] = token
    return [indexed[index] for index in range(len(indexed))]


def parse_yaml_scalar(raw: str) -> str:
    if raw.startswith("'") and raw.endswith("'"):
        return raw[1:-1].replace("''", "'")
    if not (raw.startswith('"') and raw.endswith('"')):
        return raw

    value = raw[1:-1]
    escapes = {
        "0": "\0",
        "a": "\a",
        "b": "\b",
        "t": "\t",
        "n": "\n",
        "v": "\v",
        "f": "\f",
        "r": "\r",
        "e": "\x1b",
        " ": " ",
        '"': '"',
        "/": "/",
        "\\": "\\",
        "N": "\x85",
        "_": "\xa0",
        "L": "\u2028",
        "P": "\u2029",
    }
    decoded = []
    index = 0
    while index < len(value):
        if value[index] != "\\":
            decoded.append(value[index])
            index += 1
            continue
        index += 1
        if index >= len(value):
            raise ValueError(f"invalid trailing escape in vocabulary token: {raw}")
        escape = value[index]
        index += 1
        if escape in escapes:
            decoded.append(escapes[escape])
        elif escape in {"x", "u", "U"}:
            width = {"x": 2, "u": 4, "U": 8}[escape]
            codepoint = value[index:index + width]
            if len(codepoint) != width:
                raise ValueError(f"invalid Unicode escape in vocabulary token: {raw}")
            decoded.append(chr(int(codepoint, 16)))
            index += width
        else:
            raise ValueError(f"unsupported YAML escape \\{escape} in vocabulary token")
    return "".join(decoded)


def build_compatible_sentencepiece(
    primary_path: Path,
    secondary_path: Path,
    vocab: list[str],
    output_path: Path,
) -> None:
    primary, trainer_spec, other_fields = load_sentencepiece(primary_path)
    secondary, _, _ = load_sentencepiece(secondary_path)
    language_tokens = {
        token for token in vocab if token.startswith(">>") and token.endswith("<<")
    }
    encoded = []
    for index, token in enumerate(vocab):
        if index == 0:
            score, piece_type = 0.0, 3  # CONTROL </s>
        elif index == 1:
            score, piece_type = 0.0, 2  # UNKNOWN <unk>
        elif token in primary:
            _, score, piece_type = primary[token]
        elif token in language_tokens:
            score, piece_type = 0.0, 4  # USER_DEFINED target-language token
        else:
            _, score, _ = secondary.get(token, (token, -30.0, 5))
            piece_type = 5  # UNUSED for encoding, still decodable by ID
        encoded.append(length_field(1, encode_piece(token, score, piece_type)))
    encoded.append(length_field(2, patch_trainer_spec(trainer_spec, len(vocab))))
    encoded.extend(other_fields)
    output_path.write_bytes(b"".join(encoded))


def download_source(work_dir: Path) -> Path:
    archive = work_dir / SOURCE_ARCHIVE_NAME
    if not archive.exists() or sha256(archive) != SOURCE_SHA256:
        print(f"Downloading {SOURCE_URL}")
        with urllib.request.urlopen(SOURCE_URL) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
    if sha256(archive) != SOURCE_SHA256:
        raise ValueError("OPUS source archive checksum mismatch")
    source_dir = work_dir / "source"
    source_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source_zip:
        source_zip.extractall(source_dir)
    return source_dir


def deterministic_zip(source_dir: Path, output_path: Path) -> None:
    with zipfile.ZipFile(
        output_path,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        for path in sorted(source_dir.iterdir(), key=lambda item: item.name):
            info = zipfile.ZipInfo(path.name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes(), compresslevel=9)


def write_config(path: Path) -> None:
    path.write_text(
        """beam-size: 1
normalize: 1.0
word-penalty: 0
max-length-break: 128
mini-batch-words: 1024
workspace: 128
max-length-factor: 2.5
skip-cost: true
cpu-threads: 0
quiet: true
quiet-translation: true
gemm-precision: {GEMM_TYPE}
alignment: soft
ssplit-mode: paragraph
""".format(GEMM_TYPE=GEMM_TYPE),
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--marian-conv", required=True, type=Path)
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--app-manifest", required=True, type=Path)
    parser.add_argument("--download-url", default=DEFAULT_DOWNLOAD_URL)
    args = parser.parse_args()

    args.work_dir.mkdir(parents=True, exist_ok=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    source_dir = args.source_dir or download_source(args.work_dir)
    pack_dir = args.work_dir / "pack"
    if pack_dir.exists():
        shutil.rmtree(pack_dir)
    pack_dir.mkdir()

    vocab = load_marian_vocab(source_dir / ORIGINAL_VOCAB)
    source_spm = pack_dir / "source.spm"
    target_spm = pack_dir / "target.spm"
    build_compatible_sentencepiece(
        source_dir / "source.spm",
        source_dir / "target.spm",
        vocab,
        source_spm,
    )
    build_compatible_sentencepiece(
        source_dir / "target.spm",
        source_dir / "source.spm",
        vocab,
        target_spm,
    )

    model = pack_dir / MODEL_FILE
    subprocess.run(
        [
            str(args.marian_conv),
            "--from",
            str(source_dir / ORIGINAL_MODEL),
            "--to",
            str(model),
            "--gemm-type",
            GEMM_TYPE,
        ],
        check=True,
    )

    identity_lexicon = args.work_dir / "identity.lex"
    identity_lexicon.write_text(
        "".join(f"{token}\t{token}\t1.0\n" for token in vocab[2:]),
        encoding="utf-8",
    )
    shortlist = pack_dir / "lex.s2t.bin"
    subprocess.run(
        [
            str(args.marian_conv),
            "--shortlist",
            str(identity_lexicon),
            str(SHORTLIST_FREQUENT_TOKENS),
            "1",
            "0",
            "--dump",
            str(shortlist),
            "--vocabs",
            str(source_spm),
            str(target_spm),
        ],
        check=True,
    )

    config = pack_dir / "config.yml"
    write_config(config)
    shutil.copy2(source_dir / "LICENSE", pack_dir / LICENSE_OUTPUT_NAME)

    required_names = [
        MODEL_FILE,
        "source.spm",
        "target.spm",
        "lex.s2t.bin",
        "config.yml",
    ]
    file_metadata = [
        {
            "name": name,
            "size": (pack_dir / name).stat().st_size,
            "sha256": sha256(pack_dir / name),
        }
        for name in required_names
    ]
    pack_manifest = {
        "id": MODEL_ID,
        "version": MODEL_VERSION,
        "source": SOURCE_DISPLAY_NAME,
        "sourceUrl": SOURCE_URL,
        "sourceSha256": SOURCE_SHA256,
        "license": "Apache-2.0",
        "precision": PRECISION_NAME,
        "runtimeType": "Bergamot/Marian via translate-kit",
        "runtimeVersion": RUNTIME_VERSION,
        "modelFile": MODEL_FILE,
        "supportedDirections": SUPPORTED_DIRECTIONS,
        "supportedScripts": SUPPORTED_SCRIPTS,
        "files": file_metadata,
    }
    if DEFAULT_SERBIAN_SCRIPT:
        pack_manifest["defaultSerbianScript"] = DEFAULT_SERBIAN_SCRIPT
    (pack_dir / "manifest.json").write_text(
        json.dumps(pack_manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    output_zip = args.output_dir / PACK_NAME
    deterministic_zip(pack_dir, output_zip)
    installed_size = sum(path.stat().st_size for path in pack_dir.iterdir())
    delivery_manifest = {
        **pack_manifest,
        "displayName": DISPLAY_NAME,
        "downloadUrl": args.download_url,
        "sha256": sha256(output_zip),
        "downloadSize": output_zip.stat().st_size,
        "installedSize": installed_size,
        "requiredFiles": required_names + ["manifest.json"],
    }
    args.app_manifest.parent.mkdir(parents=True, exist_ok=True)
    args.app_manifest.write_text(
        json.dumps(delivery_manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Created {output_zip} ({output_zip.stat().st_size} bytes)")
    print(f"Installed size: {installed_size} bytes")
    print(f"SHA-256: {delivery_manifest['sha256']}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise
