from pathlib import Path
import base64
import unittest

from home_reference_asset_validation import (
    decode_validated_webp_base64_file,
    validate_home_hero,
    webp_dimensions,
)

ROOT = Path(__file__).resolve().parent
ASSET = ROOT / "home-reference" / "rocky_home_reference_hero.webp.b64"


class HomeReferenceAssetValidationTests(unittest.TestCase):
    def test_checked_in_asset_is_valid_and_pinned(self):
        data = decode_validated_webp_base64_file(ASSET)
        self.assertEqual(webp_dimensions(data), (640, 640))
        self.assertEqual(validate_home_hero(data), (640, 640))

    def test_truncated_webp_is_rejected(self):
        data = decode_validated_webp_base64_file(ASSET)
        with self.assertRaises(ValueError):
            validate_home_hero(data[:-1], pin_sha256=False)

    def test_wrong_riff_length_is_rejected(self):
        data = bytearray(decode_validated_webp_base64_file(ASSET))
        data[4:8] = (len(data) - 7).to_bytes(4, "little")
        with self.assertRaises(ValueError):
            validate_home_hero(bytes(data), pin_sha256=False)

    def test_non_webp_bytes_are_rejected(self):
        with self.assertRaises(ValueError):
            validate_home_hero(b"not-a-webp", pin_sha256=False)

    def test_invalid_base64_is_rejected(self):
        with self.assertRaises(ValueError):
            base64.b64decode("%%%not-base64%%%", validate=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
