import argparse
import hashlib
import json
from pathlib import Path
import re
import time


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def interrupted(path, pid):
    events = {name: set() for name in ("started", "finished", "failed", "assumption failed")}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not re.search(rf"\s{pid}\s+\d+\s", line):
            continue
        match = re.search(r"(started|finished|failed|assumption failed): (\w+)\(([^)]+)\)", line)
        if match:
            events[match[1]].add(match[3] + "#" + match[2])
    if not events["started"] or events["started"] != events["finished"]:
        raise ValueError("Interrupted log must identify every started and finished case")
    return events


def recovered(path):
    content = path.read_text(encoding="utf-8-sig")
    passed, skipped = set(), set()
    current = {}
    completed = 0
    for line in content.splitlines():
        field = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.+)", line)
        if field:
            current[field[1]] = field[2]
        code = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if not code or int(code[1]) == 1:
            continue
        status = int(code[1])
        key = current["class"] + "#" + current["test"]
        if status not in (0, -4):
            raise ValueError(f"Recovery failure: {path}: {key}: {status}")
        (passed if status == 0 else skipped).add(key)
        completed += 1
    result = re.search(r"OK \((\d+) tests?\)", content)
    if not result or int(result[1]) != completed:
        raise ValueError(f"Incomplete recovery transcript: {path}")
    return passed, skipped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--logcat", type=Path, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--recovery", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    original = interrupted(args.logcat, args.pid)
    passed = original["finished"] - original["failed"] - original["assumption failed"]
    recovery_passed, recovery_skipped = set(), set()
    for path in args.recovery:
        success, skipped = recovered(path)
        recovery_passed.update(success)
        recovery_skipped.update(skipped)
    expected = original["started"] - original["assumption failed"]
    outstanding = expected - passed - recovery_passed
    if outstanding:
        raise ValueError("Unresolved cases: " + ", ".join(sorted(outstanding)))
    root = Path(__file__).resolve().parents[1]
    paths = [args.logcat, *args.recovery]
    report = {
        "generated_epoch": time.time(), "serial": "emulator-5580",
        "scope": "Per-case split acceptance. This is not a clean uninterrupted suite or a production release gate.",
        "source_consistency": "Recovery used the same installed app build; this report hashes the current source snapshot, not a lost pre-run snapshot.",
        "original_reported": len(original["started"]),
        "original_passed": len(passed), "original_failed": len(original["failed"]),
        "original_skipped": sorted(original["assumption failed"]),
        "recovery_passed": sorted(recovery_passed), "recovery_skipped": sorted(recovery_skipped),
        "unique_executed_passed": len(expected), "outstanding_cases": sorted(outstanding),
        "evidence_sha256": {str(path): digest(path) for path in paths},
        "current_source_sha256": {str(path.relative_to(root)): digest(path)
                                  for path in sorted((root / "app/src").rglob("*"))
                                  if path.is_file() and path.suffix in (".kt", ".java", ".xml")},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("original_reported", "original_passed", "original_failed",
                                                "unique_executed_passed", "outstanding_cases")}))


if __name__ == "__main__":
    main()
