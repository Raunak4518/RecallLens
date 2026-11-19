import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
APP = "org.photolibrary.app.feasibility"
TESTS = [
    APP + ".PersistentPhotoIndexTest#localOcrSearchReopensWithoutPlaintextInPrivateCacheOrIndex",
    APP + ".LocalVisualSearchTest#ranksRealPhotosReusesEncryptedVectorsAndPreservesOcr",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--cloud-control", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("Use a disposable emulator")
    output = ROOT / "app/build/local-privacy-verification"
    output.mkdir(parents=True, exist_ok=True)
    command = [str(args.adb), "-s", args.serial]

    def adb(*arguments, timeout=30):
        result = subprocess.run([*command, *arguments], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout, check=True)
        return result.stdout.strip()

    def shell(*arguments):
        return adb("shell", *arguments)

    def radio_state():
        return {key: shell("settings", "get", "global", key) for key in ("airplane_mode_on", "wifi_on", "mobile_data")}

    def set_radio(service, enabled):
        if service == "airplane":
            shell("cmd", "connectivity", "airplane-mode", "enable" if enabled else "disable")
        else:
            shell("svc", service, "enable" if enabled else "disable")

    def check_wifi(connected):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            status = shell("cmd", "wifi", "status")
            if connected and "Wifi is connected to" in status:
                return
            if not connected and "Wifi is disabled" in status:
                return
            time.sleep(1)
        raise RuntimeError("The emulator Wi-Fi did not reach the requested state")

    def uid(package):
        packages = shell("cmd", "package", "list", "packages", "-U", package)
        match = re.search(r"^package:" + re.escape(package) + r" uid:(\d+)$", packages, re.M)
        if not match:
            raise ValueError("Package UID not found: " + package)
        return int(match.group(1))

    def stats(uids):
        report = shell("dumpsys", "netstats")
        match = re.search(r"mAppUidStatsMap:\s+uid rxBytes rxPackets txBytes txPackets\s*\n(.*?)(?=\n\s*mStatsMapA:)", report, re.S)
        if not match:
            raise RuntimeError("Live Android UID traffic counters are unavailable")
        rows = {}
        for line in match.group(1).splitlines():
            fields = line.split()
            if len(fields) != 5 or not all(field.isdigit() for field in fields):
                raise RuntimeError("Unexpected Android UID counter row")
            rows[int(fields[0])] = [int(value) for value in fields[1:]]
        if not rows or not any(item[2] > 0 for item in rows.values()):
            raise RuntimeError("Android UID counter map has no observed traffic")
        return {package: rows.get(number, [0, 0, 0, 0]) for package, number in uids.items()}

    def workflow(state):
        transcript = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(TESTS),
                         APP + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=240)
        (output / (state + "-instrumentation.txt")).write_text(transcript, encoding="utf-8")
        if "OK (2 tests)" not in transcript or any(
            f"INSTRUMENTATION_STATUS_CODE: {code}" in transcript for code in (-2, -3, -4)
        ):
            raise RuntimeError(f"{state} local search did not pass both tests")
        print(state + " local OCR and visual search passed", flush=True)

    def delta(before, after):
        changes = {package: dict(zip(("rx_bytes", "rx_packets", "tx_bytes", "tx_packets"),
                                    (end - start for start, end in zip(before[package], after[package]))))
                   for package in before}
        if any(amount < 0 for counters in changes.values() for amount in counters.values()):
            raise RuntimeError("UID counters reset during a connected run")
        return changes

    initial = radio_state()
    if any(value not in ("0", "1") for value in initial.values()):
        raise RuntimeError("Could not record the original emulator radio settings")
    artifacts = {}
    for apk in (ROOT / "app/build/outputs/apk/feasibility/app-feasibility.apk",
                ROOT / "app/build/outputs/apk/androidTest/feasibility/app-feasibility-androidTest.apk"):
        if "Success" not in adb("install", "-r", str(apk), timeout=120):
            raise RuntimeError("APK install did not report success")
        with apk.open("rb") as stream:
            artifacts[apk.name] = hashlib.file_digest(stream, "sha256").hexdigest()
    uids = {package: uid(package) for package in (APP, APP + ".test")}
    try:
        set_radio("airplane", False)
        set_radio("wifi", True)
        check_wifi(True)
        before = stats(uids)
        workflow("connected")
        time.sleep(30)
        observed = delta(before, stats(uids))
        set_radio("airplane", True)
        set_radio("wifi", False)
        set_radio("data", False)
        check_wifi(False)
        offline_state = radio_state()
        if offline_state != {"airplane_mode_on": "1", "wifi_on": "0", "mobile_data": "0"}:
            raise RuntimeError("Could not confirm offline emulator settings")
        workflow("offline")
    finally:
        for service, setting in (("airplane", "airplane_mode_on"), ("wifi", "wifi_on"), ("data", "mobile_data")):
            set_radio(service, initial[setting] == "1")
    restored = radio_state()
    if restored != initial:
        raise RuntimeError("The emulator radio settings did not restore")
    receipt = {"serial": args.serial, "android_sdk": shell("getprop", "ro.build.version.sdk"),
               "apk_sha256": artifacts, "tests": TESTS,
               "observed_uid_delta": observed, "uids": uids, "offline_settings": offline_state,
               "original_settings": initial, "restored_settings": restored, "post_run_observation_seconds": 30,
               "scope": "Direct app/test UID traffic only. Delayed SDK jobs and activity under other UIDs are outside this measurement."}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(observed), flush=True)
    if args.cloud_control:
        check_wifi(True)
        before = stats(uids)
        subprocess.run([sys.executable, "tools/run_cloud_service_acceptance.py", "--adb", str(args.adb),
                        "--serial", args.serial], cwd=ROOT, check=True)
        for waited in range(0, 31, 2):
            control = delta(before, stats(uids))
            if control[APP]["rx_bytes"] > 0 and control[APP]["tx_bytes"] > 0:
                receipt["cloud_positive_control"] = {"observed_uid_delta": control,
                                                     "post_run_wait_seconds": waited}
                (output / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
                print("Consented cloud control observed by UID counters: " + json.dumps(control), flush=True)
                break
            time.sleep(2)
        else:
            raise RuntimeError("UID counters did not detect the consented cloud control")


if __name__ == "__main__":
    main()
