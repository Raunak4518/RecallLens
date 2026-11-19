import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

from tools.inspect_release import inspect_runtime_notice_archive, runtime_notice_index


class RuntimeNoticeInspectionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.source = Path(temporary.name)
        self.text = b"Example runtime notice\n"
        self.sha = hashlib.sha256(self.text).hexdigest()
        self.path = f"texts/{self.sha}.txt"
        self.coordinate = "org.example:runtime:1.0"
        self.artifact_sha = "1" * 64
        (self.source / "texts").mkdir()
        (self.source / self.path).write_bytes(self.text)
        self.index = {
            "version": 1,
            "components": [{"coordinate": self.coordinate, "notice_sha256": [self.sha],
                            "artifact_sha256": [self.artifact_sha]}],
            "documents": [{"path": self.path, "sha256": self.sha, "bytes": len(self.text),
                           "mappings": [{"coordinate": self.coordinate, "artifact_sha256": [self.artifact_sha]}]}],
        }
        self.write_index()

    def write_index(self):
        (self.source / "index.json").write_text(json.dumps(self.index), encoding="utf-8")

    def inspect(self, entries, bundle=False):
        expected, component_count, mapping_count = runtime_notice_index(self.source)
        self.assertEqual((1, 1), (component_count, mapping_count))
        file = io.BytesIO()
        with zipfile.ZipFile(file, "w") as archive, warnings.catch_warnings():
            warnings.filterwarnings("ignore", message="Duplicate name:", category=UserWarning)
            for name, data in entries:
                archive.writestr(name, data)
        with zipfile.ZipFile(file) as archive:
            return inspect_runtime_notice_archive(archive, expected, bundle=bundle)

    def entries(self, prefix):
        return [(prefix + "index.json", (self.source / "index.json").read_bytes()), (prefix + self.path, self.text)]

    def test_exact_index_and_text_pass_in_both_archive_layouts(self):
        for bundle in (False, True):
            prefix = ("base/" if bundle else "") + "assets/third-party-runtime/"
            entries = self.entries(prefix) + [(prefix, b""), (prefix + "texts/", b""), ("unrelated", b"x")]
            self.assertFalse(any(self.inspect(entries, bundle).values()))

    def test_archive_rejects_missing_tampered_duplicate_and_undeclared_entries(self):
        prefix = "assets/third-party-runtime/"
        index_entry, notice_entry = self.entries(prefix)
        self.assertEqual([index_entry[0]], self.inspect([notice_entry])["missing"])
        changed = [(index_entry[0], b"!" + index_entry[1][1:]), (notice_entry[0], b"!" + self.text[1:])]
        self.assertEqual([index_entry[0], notice_entry[0]], self.inspect(changed)["mismatched"])
        self.assertEqual([notice_entry[0]], self.inspect([index_entry, notice_entry, notice_entry])["duplicate"])
        extra = prefix + "old.txt"
        self.assertEqual([extra], self.inspect([index_entry, notice_entry, (extra, b"legacy")])["unexpected"])

    def test_bundle_rejects_an_extra_copy_in_another_module(self):
        entries = self.entries("base/assets/third-party-runtime/")
        extra = "feature/assets/third-party-runtime/index.json"
        self.assertEqual([extra], self.inspect(entries + [(extra, b"{}")], bundle=True)["unexpected"])

    def test_empty_upstream_notice_retains_its_exact_digest(self):
        (self.source / self.path).unlink()
        self.text = b""
        self.sha = hashlib.sha256(self.text).hexdigest()
        self.path = f"texts/{self.sha}.txt"
        (self.source / self.path).write_bytes(self.text)
        self.index["components"][0]["notice_sha256"] = [self.sha]
        self.index["documents"][0].update(path=self.path, sha256=self.sha, bytes=0)
        self.write_index()
        prefix = "assets/third-party-runtime/"
        self.assertFalse(any(self.inspect(self.entries(prefix)).values()))
        self.assertEqual([prefix + self.path], self.inspect(self.entries(prefix)[:1] + [(prefix + self.path, b"x")])["mismatched"])

    def test_index_rejects_duplicate_notice_and_inconsistent_component_artifacts(self):
        self.index["documents"].append(self.index["documents"][0])
        self.write_index()
        with self.assertRaisesRegex(ValueError, "duplicate runtime notice document"):
            runtime_notice_index(self.source)
        self.index["documents"].pop()
        self.index["components"][0]["artifact_sha256"] = ["2" * 64]
        self.write_index()
        with self.assertRaisesRegex(ValueError, "mapping differs"):
            runtime_notice_index(self.source)

    def test_source_rejects_changed_or_undeclared_notice_bytes(self):
        notice = self.source / self.path
        notice.write_bytes(b"!" + self.text[1:])
        with self.assertRaisesRegex(ValueError, "differs from its index"):
            runtime_notice_index(self.source)
        notice.write_bytes(self.text)
        (self.source / "old.txt").write_bytes(b"old")
        with self.assertRaisesRegex(ValueError, "Undeclared runtime notice source"):
            runtime_notice_index(self.source)


if __name__ == "__main__":
    unittest.main()
