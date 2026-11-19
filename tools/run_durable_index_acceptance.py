import argparse
import json
import subprocess
import time
from pathlib import Path


APP = "org.photolibrary.app.feasibility"
CLASS = "org.photolibrary.app.feasibility.DurableWorkerAcceptanceTest"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--restart-mode", choices=("process", "reboot"), default="process")
    parser.add_argument("--boot-timeout", type=int, default=180)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("This acceptance script operates only on a disposable emulator")
    if args.boot_timeout <= 0:
        raise ValueError("Boot timeout must be positive")
    if args.output is None:
        suffix = "-reboot" if args.restart_mode == "reboot" else ""
        args.output = Path("app/build/durable-worker-acceptance" + suffix)
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*command):
        result = subprocess.run([str(args.adb), "-s", args.serial, *command], capture_output=True, text=True, encoding="utf-8")
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    def phase(method):
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "durableWorkerAcceptance", "true",
                     "-e", "class", CLASS + "#" + method, RUNNER)
        (args.output / (method + ".txt")).write_text(output, encoding="utf-8")
        if "OK (1 test)" not in output:
            raise RuntimeError(output)
        print(method + " passed", flush=True)

    def restart():
        boot_before = adb("shell", "cat", "/proc/sys/kernel/random/boot_id").strip()
        process = subprocess.run([str(args.adb), "-s", args.serial, "shell", "pidof", APP],
                                 capture_output=True, text=True, encoding="utf-8")
        old_pid = process.stdout.strip() if process.returncode == 0 else "already stopped"
        if args.restart_mode == "reboot":
            adb("reboot")
            deadline = time.monotonic() + args.boot_timeout
            while True:
                probe = subprocess.run([str(args.adb), "-s", args.serial, "shell", "getprop", "sys.boot_completed"],
                                       capture_output=True, text=True, encoding="utf-8", timeout=10)
                if probe.returncode == 0 and probe.stdout.strip() == "1":
                    break
                if time.monotonic() >= deadline:
                    raise TimeoutError("Emulator did not complete reboot")
                time.sleep(1)
            adb("shell", "input", "keyevent", "82")
        else:
            adb("shell", "am", "force-stop", APP)
        adb("shell", "am", "start", "-W", "-n", APP + "/org.photolibrary.app.MainActivity")
        new_pid = adb("shell", "pidof", APP).strip()
        boot_after = adb("shell", "cat", "/proc/sys/kernel/random/boot_id").strip()
        if args.restart_mode == "reboot" and boot_before == boot_after:
            raise RuntimeError("Emulator boot identity did not change")
        if boot_before == boot_after and old_pid == new_pid:
            raise RuntimeError("The application process did not restart")
        return {"before": old_pid, "after": new_pid, "boot_before": boot_before, "boot_after": boot_after}

    phase("prepareAndWaitForPartialProgress")
    first = restart()
    phase("verifyResumeAndSetPersistentPause")
    second = restart()
    phase("verifyPersistentPauseResumeAndCleanup")
    receipt = json.loads(adb("exec-out", "run-as", APP, "cat", "files/durable-worker-acceptance.json"))
    receipt["host_process_restarts"] = [first, second]
    receipt["restart_mode"] = args.restart_mode
    (args.output / "results.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"completed_photos": receipt["completed_photos"], "pause_survived_restart": receipt["pause_survived_restart"]}))


if __name__ == "__main__":
    main()
