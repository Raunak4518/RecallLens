import hashlib
import io
import json
import unittest

from PIL import Image

from tools.vlm_eval.image_input import POLICY, policy_receipt, render_image


class ImageInputTest(unittest.TestCase):
    def jpeg(self, size, orientation=None, metadata=False):
        with Image.new("RGB", size, (34, 67, 109)) as image:
            exif = Image.Exif()
            if orientation is not None:
                exif[274] = orientation
            if metadata:
                exif[315] = "PRIVATE FIXTURE AUTHOR"
                exif[270] = "PRIVATE FIXTURE DESCRIPTION"
            output = io.BytesIO()
            options = {"exif": exif}
            if metadata:
                options.update(icc_profile=b"PRIVATE FIXTURE ICC", comment=b"PRIVATE FIXTURE COMMENT")
            image.save(output, "JPEG", **options)
            return output.getvalue()

    def render(self, source):
        return render_image(source, hashlib.sha256(source).hexdigest())

    def test_exif_rotation_applies_before_aspect_preserving_downscale(self):
        rendered, metadata = self.render(self.jpeg((800, 400), orientation=6))
        with Image.open(io.BytesIO(rendered)) as image:
            self.assertEqual((192, 384), image.size)
            self.assertEqual("RGB", image.mode)
            self.assertEqual("JPEG", image.format)
        self.assertEqual((192, 384), (metadata["render_width"], metadata["render_height"]))

    def test_no_upscale_and_landscape_portrait_bounds_are_deterministic(self):
        for original, expected in [((80, 40), (80, 40)), ((1000, 250), (384, 96)), ((250, 1000), (96, 384)), ((900, 900), (384, 384))]:
            with self.subTest(original=original):
                source = self.jpeg(original)
                first, metadata = self.render(source)
                second, other = self.render(source)
                self.assertEqual(first, second)
                self.assertEqual(metadata, other)
                with Image.open(io.BytesIO(first)) as image:
                    self.assertEqual(expected, image.size)
                self.assertEqual(hashlib.sha256(first).hexdigest(), metadata["render_sha256"])
                self.assertEqual(len(first), metadata["render_bytes"])

    def test_source_metadata_is_not_copied_to_rendered_jpeg(self):
        source = self.jpeg((800, 400), orientation=6, metadata=True)
        self.assertIn(b"PRIVATE FIXTURE", source)
        rendered, _ = self.render(source)
        self.assertNotIn(b"PRIVATE FIXTURE", rendered)
        with Image.open(io.BytesIO(rendered)) as image:
            self.assertEqual({}, dict(image.getexif()))
            for name in ("exif", "icc_profile", "comment", "xmp"):
                self.assertNotIn(name, image.info)

    def test_render_uses_only_supplied_verified_source_bytes(self):
        source = self.jpeg((100, 60))
        before = bytes(source)
        self.render(source)
        self.assertEqual(before, source)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            render_image(source, "0" * 64)
        with self.assertRaises(ValueError):
            render_image(b"", hashlib.sha256(b"").hexdigest())
        with self.assertRaises(ValueError):
            render_image(b"x" * (POLICY["source_max_bytes"] + 1), "0" * 64)
        png = io.BytesIO()
        Image.new("RGB", (4, 4)).save(png, "PNG")
        data = png.getvalue()
        with self.assertRaises(ValueError):
            render_image(data, hashlib.sha256(data).hexdigest())

    def test_policy_receipt_pins_encoder_settings_and_pillow(self):
        receipt = policy_receipt()
        self.assertEqual(hashlib.sha256(json.dumps(POLICY, sort_keys=True, separators=(",", ":")).encode()).hexdigest(), receipt["image_policy_sha256"])
        self.assertTrue(receipt["pillow_version"])
        self.assertEqual(384, receipt["image_policy"]["longest_edge"])
        self.assertFalse(receipt["image_policy"]["upscale"])
        self.assertEqual((90, 2, False, False), tuple(receipt["image_policy"][name] for name in ("quality", "subsampling", "optimize", "progressive")))


if __name__ == "__main__":
    unittest.main()
