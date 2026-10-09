from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
VIEW = ROOT / "home-reference" / "ReferenceHomeVisualView.kt"
OVERLAY = ROOT / "apply-product-home-overlay.py"


class HomePulseContractTests(unittest.TestCase):
    def test_pulse_is_an_explicit_training_intent_api(self):
        view = VIEW.read_text(encoding="utf-8")
        self.assertIn("fun playTrainingIntentPulse(trainingIntentEventId: String", view)
        self.assertIn("lastTrainingIntentEventId", view)
        self.assertIn("PULSE_DURATION_MS", view)
        self.assertNotIn("drawArc(", view)
        self.assertNotIn("cadence", view.lower())
        self.assertNotIn("override fun onAttachedToWindow()", view)

    def test_only_home_training_cta_starts_pulse(self):
        overlay = OVERLAY.read_text(encoding="utf-8")
        self.assertEqual(overlay.count("hero.playTrainingIntentPulse(trainingIntentEventId)"), 1)
        self.assertIn("java.util.UUID.randomUUID().toString()", overlay)
        self.assertIn("showLesson(nextLesson.first, nextLesson.second)", overlay)

    def test_reference_asset_is_validated_before_writing_android_resource(self):
        overlay = OVERLAY.read_text(encoding="utf-8")
        self.assertIn("decode_validated_webp_base64_file", overlay)
        self.assertIn("REFERENCE_HOME_ASSET.write_bytes", overlay)


if __name__ == "__main__":
    unittest.main(verbosity=2)
