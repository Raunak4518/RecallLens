import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(stream):
    return hashlib.file_digest(stream, "sha256").hexdigest()


def artifact(path):
    with path.open("rb") as stream:
        return {"path": str(path.relative_to(ROOT)), "bytes": path.stat().st_size, "sha256": digest(stream)}


def runtime_notice_index(directory):
    index_path = directory / "index.json"
    if not 0 < index_path.stat().st_size <= 512 * 1024:
        raise ValueError("Runtime notice index exceeds its supported size")
    index_bytes = index_path.read_bytes()
    index = json.loads(index_bytes.decode("utf-8"))
    if index["version"] != 1 or not index["documents"] or not index["components"]:
        raise ValueError("Expected a populated version 1 runtime notice index")
    components_by_name = {row["coordinate"]: row for row in index["components"]}
    if len(components_by_name) != len(index["components"]):
        raise ValueError("Duplicate runtime notice component")
    expected = {"index.json": (len(index_bytes), hashlib.sha256(index_bytes).hexdigest())}
    mappings = set()
    hashes = set()
    for document in index["documents"]:
        sha256 = document["sha256"]
        path = document["path"]
        size = document["bytes"]
        if (not re.fullmatch(r"[0-9a-f]{64}", sha256) or sha256 in hashes
                or path not in (f"texts/{sha256}.txt", f"texts/{sha256}.json")
                or type(size) is not int or not 0 <= size <= 4 * 1024 * 1024):
            raise ValueError(f"Invalid or duplicate runtime notice document: {path}")
        hashes.add(sha256)
        source = directory / path
        with source.open("rb") as stream:
            if source.stat().st_size != size or digest(stream) != sha256:
                raise ValueError(f"Runtime notice source differs from its index: {path}")
        expected[path] = (size, sha256)
        if not document["mappings"]:
            raise ValueError(f"Runtime notice is missing component provenance: {path}")
        for mapping in document["mappings"]:
            coordinate = mapping["coordinate"]
            component = components_by_name.get(coordinate)
            if (component is None or (coordinate, sha256) in mappings
                    or not mapping["artifact_sha256"]
                    or set(mapping["artifact_sha256"]) != set(component["artifact_sha256"])):
                raise ValueError(f"Runtime notice mapping differs from its component: {coordinate}")
            mappings.add((coordinate, sha256))
    declared_mappings = [(component["coordinate"], sha256) for component in index["components"]
                         for sha256 in component["notice_sha256"]]
    if len(declared_mappings) != len(mappings) or set(declared_mappings) != mappings:
        raise ValueError("Runtime notice component links differ from document mappings")
    actual = {path.relative_to(directory).as_posix() for path in directory.rglob("*") if path.is_file()}
    if actual != expected.keys():
        raise ValueError(f"Undeclared runtime notice source files: {sorted(actual - expected.keys())}")
    return expected, len(components_by_name), len(mappings)


def inspect_runtime_notice_archive(archive, expected, bundle=False):
    namespace = "assets/third-party-runtime"
    prefix = ("base/" if bundle else "") + namespace + "/"
    pattern = re.compile((r"[^/]+/" if bundle else "") + re.escape(namespace) + r"(?:/.*)?")
    entries = [entry for entry in archive.infolist() if pattern.fullmatch(entry.filename)]
    counts = Counter(entry.filename for entry in entries)
    by_name = {entry.filename: entry for entry in entries}
    files = {prefix + path for path in expected}
    allowed = files | {prefix, prefix + "texts/"}
    mismatched = []
    for path, (size, sha256) in expected.items():
        name = prefix + path
        if counts[name] != 1:
            continue
        entry = by_name[name]
        if entry.file_size != size:
            mismatched.append(name)
            continue
        with archive.open(entry) as stream:
            if digest(stream) != sha256:
                mismatched.append(name)
    return {"missing": sorted(files - counts.keys()), "unexpected": sorted(counts.keys() - allowed),
            "duplicate": sorted(name for name, count in counts.items() if count > 1),
            "mismatched": sorted(mismatched)}


def load_alignments(data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected little-endian ELF64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or offset + size * count > len(data):
        raise ValueError("Invalid ELF program headers")
    return [struct.unpack_from("<Q", data, offset + index * size + 48)[0]
            for index in range(count) if struct.unpack_from("<I", data, offset + index * size)[0] == 1]


def components(manifest):
    records = []
    current = None
    depth = 0
    for line in manifest.splitlines():
        indent = len(line) - len(line.lstrip())
        element = re.match(r"\s*E: (activity|activity-alias|service|receiver|provider) ", line)
        if element:
            current = {"kind": element.group(1)}
            records.append(current)
            depth = indent
        elif current is not None and indent <= depth:
            current = None
        elif current is not None and indent == depth + 2:
            attribute = re.search(r':(name|permission|exported)\([^)]*\)=("[^"]*"|true|false)', line)
            if attribute:
                current[attribute.group(1)] = attribute.group(2).strip('"')
    return records


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--bundle", type=Path, default=ROOT / "app/build/outputs/bundle/release/app-release.aab")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/release-inspection")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    resources = subprocess.run([str(args.build_tools / "aapt2.exe"), "dump", "resources", str(args.apk)],
                               capture_output=True, text=True, encoding="utf-8", check=True).stdout

    def xml(name):
        packaged = name
        if name.startswith("res/xml/"):
            logical = "xml/" + Path(name).stem
            match = re.search(r"resource (0x[0-9a-f]+) " + re.escape(logical) + r"\s+\(\) \(file\) (res/\S+)", resources)
            if not match:
                raise ValueError(f"Missing release resource: {logical}")
            packaged = match.group(2)
        result = subprocess.run([str(args.build_tools / "aapt2.exe"), "dump", "xmltree", str(args.apk),
                                 "--file", packaged], capture_output=True, text=True, encoding="utf-8", check=True)
        (args.output / (Path(name).stem + ".txt")).write_text(result.stdout, encoding="utf-8")
        return result.stdout

    manifest = xml("AndroidManifest.xml")
    network = xml("res/xml/network_security_config.xml")
    backups = xml("res/xml/data_extraction_rules.xml")
    exported = [item for item in components(manifest) if item.get("exported") == "true"]
    allowed_exports = {
        "org.photolibrary.app.MainActivity": None,
        "androidx.work.impl.background.systemjob.SystemJobService": "android.permission.BIND_JOB_SERVICE",
        "androidx.work.impl.diagnostics.DiagnosticsReceiver": "android.permission.DUMP",
        "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
    }
    checks = {
        "backup_disabled": bool(re.search(r":allowBackup\([^\n]+\)=false", manifest)),
        "full_backup_disabled": bool(re.search(r":fullBackupContent\([^\n]+\)=false", manifest)),
        "not_debuggable": not bool(re.search(r":debuggable\([^\n]+\)=true", manifest)),
        "cleartext_disabled": bool(re.search(r"cleartextTrafficPermitted[^\n]*=false", network))
                              and "=true" not in network and "domain-config" not in network,
        "both_backup_channels_excluded": backups.count("E: exclude ") == 18
                                         and "E: cloud-backup " in backups and "E: device-transfer " in backups,
        "expected_exported_components": {item.get("name"): item.get("permission") for item in exported} == allowed_exports,
    }
    with zipfile.ZipFile(args.apk) as apk, zipfile.ZipFile(args.bundle) as bundle:
        entries = apk.infolist()
        natives = [entry for entry in entries if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        checks["arm64_only"] = {entry.filename.split("/")[1] for entry in natives} == {"arm64-v8a"}
        native_records = []
        for entry in natives:
            alignments = load_alignments(apk.read(entry))
            native_records.append({"path": entry.filename, "bytes": entry.file_size, "load_alignments": alignments})
        checks["elf_16k_compatible"] = all(item["load_alignments"] and min(item["load_alignments"]) >= 16384
                                              for item in native_records)
        staged = []
        for path in sorted((ROOT / ".local-models").rglob("*")):
            if not path.is_file():
                continue
            name = "assets/" + path.relative_to(ROOT / ".local-models").as_posix()
            with path.open("rb") as stream:
                expected = digest(stream)
            with apk.open(name) as stream:
                packaged = digest(stream)
            with bundle.open("base/" + name) as stream:
                bundled = digest(stream)
            staged.append({"path": name, "bytes": path.stat().st_size, "sha256": expected,
                           "apk_matches": expected == packaged, "bundle_matches": expected == bundled})
        checks["staged_assets_match"] = bool(staged) and all(item["apk_matches"] and item["bundle_matches"] for item in staged)
        notices = []
        for path in sorted((ROOT / "app/src/main/assets/third-party-models").glob("*")):
            if not path.is_file():
                continue
            name = "assets/third-party-models/" + path.name
            with path.open("rb") as stream:
                expected = digest(stream)
            with apk.open(name) as stream:
                packaged = digest(stream)
            with bundle.open("base/" + name) as stream:
                bundled = digest(stream)
            notices.append({"path": name, "sha256": expected, "apk_matches": packaged == expected,
                            "bundle_matches": bundled == expected})
        checks["model_notices_match"] = bool(notices) and all(item["apk_matches"] and item["bundle_matches"] for item in notices)
        runtime_source = ROOT / "app/src/main/assets/third-party-runtime"
        runtime_expected, runtime_components, runtime_mappings = runtime_notice_index(runtime_source)
        runtime_notices = {
            "components": runtime_components,
            "mappings": runtime_mappings,
            "files": [{"path": path, "bytes": size, "sha256": sha256}
                      for path, (size, sha256) in sorted(runtime_expected.items())],
            "apk": inspect_runtime_notice_archive(apk, runtime_expected),
            "bundle": inspect_runtime_notice_archive(bundle, runtime_expected, bundle=True),
        }
        checks["runtime_notices_match"] = not any(
            findings for kind in ("apk", "bundle") for findings in runtime_notices[kind].values())
        model_bytes = sum(entry.file_size for entry in entries if entry.filename.endswith((".onnx", ".tflite")))
    alignment = subprocess.run([str(args.build_tools / "zipalign.exe"), "-c", "-P", "16", "4", str(args.apk)],
                               capture_output=True, text=True, encoding="utf-8")
    (args.output / "zipalign.txt").write_text(alignment.stdout + alignment.stderr, encoding="utf-8")
    checks["apk_zip_alignment"] = alignment.returncode == 0
    report = {"apk": artifact(args.apk), "bundle": artifact(args.bundle), "checks": checks,
              "native_libraries": native_records, "staged_assets": staged, "model_notices": notices,
              "runtime_notices": runtime_notices, "model_bytes": model_bytes,
              "exported_components": exported,
              "native_bytes": sum(item["bytes"] for item in native_records),
              "limits": "Static packaging checks only. No signing, Play approval, physical performance or download-size claim."}
    (args.output / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"checks": checks, "apk_bytes": report["apk"]["bytes"], "bundle_bytes": report["bundle"]["bytes"]}))
    if not all(checks.values()):
        raise SystemExit("Release inspection has failed checks; see results.json")


if __name__ == "__main__":
    main()
