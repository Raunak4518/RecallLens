import hashlib
import json
import sys
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "app" / "build" / "garment-model"
REVISION = "7142c7f9d2be9ee39a47bd2091ad9d5fbe61bb1b"
REPOSITORY = "https://huggingface.co/mattmdjaga/segformer_b0_clothes"


def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    manifest = {"repository": REPOSITORY, "revision": REVISION, "evaluation_only": True, "files": {}}
    for name in ("config.json", "preprocessor_config.json", "model.safetensors", "README.md"):
        path = DESTINATION / name
        if not path.is_file():
            with urlopen(f"{REPOSITORY}/resolve/{REVISION}/{name}", timeout=60) as response:
                data = response.read(20_000_001)
                if len(data) > 20_000_000:
                    raise ValueError("Unexpected model size")
                path.write_bytes(data)
        manifest["files"][name] = hashlib.sha256(path.read_bytes()).hexdigest()
        print(name, path.stat().st_size, flush=True)
    license_path = DESTINATION / "upstream-SegFormer-LICENSE.txt"
    if not license_path.is_file():
        with urlopen("https://raw.githubusercontent.com/NVlabs/SegFormer/master/LICENSE", timeout=30) as response:
            license_path.write_bytes(response.read(20_000))
    manifest["files"][license_path.name] = hashlib.sha256(license_path.read_bytes()).hexdigest()
    (DESTINATION / "provenance.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    sys.path.insert(0, str(ROOT / "app" / "build" / "garment-export-runtime"))
    import torch
    from transformers import SegformerForSemanticSegmentation

    class Logits(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, pixels):
            return self.model(pixel_values=pixels).logits

    model = Logits(SegformerForSemanticSegmentation.from_pretrained(DESTINATION, local_files_only=True)).eval()
    destination = DESTINATION / "upper-clothes.onnx"
    if not destination.is_file():
        with torch.inference_mode():
            torch.onnx.export(model, torch.zeros(1, 3, 512, 512), destination,
                              input_names=["pixels"], output_names=["logits"], opset_version=17, dynamo=False)
    manifest["files"][destination.name] = hashlib.sha256(destination.read_bytes()).hexdigest()
    (DESTINATION / "provenance.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("Exported", destination, destination.stat().st_size, flush=True)


if __name__ == "__main__":
    main()
