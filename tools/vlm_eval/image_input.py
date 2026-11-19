import hashlib
import io
import json

import PIL
from PIL import Image, ImageOps


POLICY = {
    "version": "vlm-pilot-bounded-jpeg-v1",
    "source": "Same SHA-256-verified original JPEG bytes, decoded from BytesIO",
    "source_max_bytes": 8 * 1024**2,
    "source_max_pixels": 64_000_000,
    "orientation": "Pillow ImageOps.exif_transpose",
    "color": "Pillow RGB conversion, no ICC color transform",
    "longest_edge": 384,
    "upscale": False,
    "resampling": "LANCZOS",
    "reducing_gap": 2.0,
    "output": "JPEG",
    "quality": 90,
    "subsampling": 2,
    "optimize": False,
    "progressive": False,
    "metadata": "Fresh RGB image; no EXIF, ICC, comment, XMP or source metadata passed to encoder",
    "storage": "Memory only; no rendered image file persisted",
}


def policy_receipt():
    encoded = json.dumps(POLICY, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {"image_policy": dict(POLICY), "image_policy_sha256": hashlib.sha256(encoded).hexdigest(), "pillow_version": PIL.__version__}


def render_image(source_bytes, expected_sha256):
    if not isinstance(source_bytes, bytes) or not 0 < len(source_bytes) <= POLICY["source_max_bytes"]:
        raise ValueError("Original source bytes exceed bounds")
    if hashlib.sha256(source_bytes).hexdigest() != expected_sha256:
        raise ValueError("Original source bytes do not match the frozen SHA-256")
    with Image.open(io.BytesIO(source_bytes)) as original:
        if original.format != "JPEG" or original.width * original.height > POLICY["source_max_pixels"]:
            raise ValueError("Need a bounded original JPEG")
        with ImageOps.exif_transpose(original) as oriented:
            with oriented.convert("RGB") as rgb:
                rgb.thumbnail((POLICY["longest_edge"], POLICY["longest_edge"]), Image.Resampling.LANCZOS, reducing_gap=2.0)
                with Image.new("RGB", rgb.size) as clean:
                    clean.paste(rgb)
                    output = io.BytesIO()
                    clean.save(output, format="JPEG", quality=90, subsampling=2, optimize=False, progressive=False)
                    rendered = output.getvalue()
                    return rendered, {"render_sha256": hashlib.sha256(rendered).hexdigest(),
                        "render_width": clean.width, "render_height": clean.height, "render_bytes": len(rendered)}
