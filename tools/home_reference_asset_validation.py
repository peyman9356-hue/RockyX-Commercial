from __future__ import annotations

import base64
import hashlib
from pathlib import Path
from typing import Tuple

EXPECTED_HOME_HERO_SHA256 = "7bf105a86bc7c468d9ec23dda944b1d447fa9218d82a84b418a85bb2a65b1b5e"
EXPECTED_HOME_HERO_SIZE = (640, 640)


def webp_dimensions(data: bytes) -> Tuple[int, int]:
    """Validate RIFF/WebP container lengths and parse an image's dimensions."""
    if len(data) < 30:
        raise ValueError("Home hero WebP is too short")
    if data[0:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise ValueError("Home hero asset is not a RIFF/WebP file")

    declared_riff_size = int.from_bytes(data[4:8], "little")
    if declared_riff_size != len(data) - 8:
        raise ValueError(
            f"WebP RIFF length mismatch: header={declared_riff_size + 8}, actual={len(data)}"
        )

    offset = 12
    dimensions = None
    image_chunk_count = 0
    while offset < len(data):
        if offset + 8 > len(data):
            raise ValueError("Truncated WebP chunk header")
        chunk_type = data[offset:offset + 4]
        chunk_size = int.from_bytes(data[offset + 4:offset + 8], "little")
        payload_start = offset + 8
        payload_end = payload_start + chunk_size
        if payload_end > len(data):
            raise ValueError(f"Truncated WebP chunk: {chunk_type!r}")

        payload = data[payload_start:payload_end]
        if chunk_type == b"VP8 ":
            if len(payload) < 10 or payload[3:6] != b"\x9d\x01\x2a":
                raise ValueError("Malformed VP8 frame header")
            width = int.from_bytes(payload[6:8], "little") & 0x3FFF
            height = int.from_bytes(payload[8:10], "little") & 0x3FFF
            dimensions = (width, height)
            image_chunk_count += 1
        elif chunk_type == b"VP8L":
            if len(payload) < 5 or payload[0] != 0x2F:
                raise ValueError("Malformed VP8L frame header")
            b1, b2, b3, b4 = payload[1:5]
            width = 1 + (((b2 & 0x3F) << 8) | b1)
            height = 1 + (((b4 & 0x0F) << 10) | (b3 << 2) | ((b2 & 0xC0) >> 6))
            dimensions = (width, height)
            image_chunk_count += 1
        elif chunk_type == b"VP8X":
            if len(payload) < 10:
                raise ValueError("Malformed VP8X frame header")
            width = 1 + int.from_bytes(payload[4:7], "little")
            height = 1 + int.from_bytes(payload[7:10], "little")
            dimensions = (width, height)

        offset = payload_end + (chunk_size & 1)

    if offset != len(data):
        raise ValueError("WebP chunk alignment does not end at EOF")
    if dimensions is None:
        raise ValueError("WebP contains no recognized image frame")
    width, height = dimensions
    if width < 600 or height < 600 or width > 1200 or height > 1200:
        raise ValueError(f"Unexpected Home hero dimensions: {width}x{height}")
    if width != height:
        raise ValueError(f"Home hero must remain square, got {width}x{height}")
    return dimensions


def validate_home_hero(data: bytes, *, pin_sha256: bool = True) -> Tuple[int, int]:
    dimensions = webp_dimensions(data)
    if dimensions != EXPECTED_HOME_HERO_SIZE:
        raise ValueError(
            f"Home hero dimensions changed without updating the visual lock: {dimensions}"
        )
    if pin_sha256:
        actual_hash = hashlib.sha256(data).hexdigest()
        if actual_hash != EXPECTED_HOME_HERO_SHA256:
            raise ValueError(
                f"Home hero SHA-256 mismatch: expected={EXPECTED_HOME_HERO_SHA256}, actual={actual_hash}"
            )
    return dimensions


def decode_validated_webp_base64_file(path: Path) -> bytes:
    """Decode the checked-in text asset and fail before it enters the Android source set."""
    encoded = path.read_text(encoding="ascii").strip()
    try:
        data = base64.b64decode(encoded, validate=True)
    except Exception as exc:
        raise ValueError(f"Home hero base64 is invalid: {path}") from exc
    validate_home_hero(data)
    return data
