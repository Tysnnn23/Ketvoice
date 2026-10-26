"""Render the built iPhone app on an isolated iOS simulator; never a physical-device check."""
import argparse
import json
from pathlib import Path
import subprocess
import time


def run(*arguments, timeout=120):
    return subprocess.check_output(["xcrun", "simctl", *arguments], text=True, timeout=timeout).strip()


def render(app: Path, output: Path):
    inventory = json.loads(run("list", "--json"))
    runtimes = [runtime for runtime in inventory["runtimes"]
                if runtime.get("isAvailable") and runtime["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]
    if not runtimes:
        raise RuntimeError("No iOS simulator runtime available")
    runtime = max(runtimes, key=lambda value: tuple(int(part) for part in value["version"].split(".")))
    devices = [device for device in inventory["devicetypes"] if device["name"].startswith("iPhone")]
    preferred = next((device for device in devices if device["name"] == "iPhone 17"), devices[-1])
    device_id = run("create", "Ketvoice-CI", preferred["identifier"], runtime["identifier"])
    output.mkdir(parents=True, exist_ok=True)
    try:
        run("boot", device_id)
        run("bootstatus", device_id, "-b", timeout=300)
        run("status_bar", device_id, "override", "--time", "9:41", "--batteryState", "charged", "--batteryLevel", "100")
        run("install", device_id, str(app))
        run("launch", device_id, "com.ketvoice.ios")
        # Allow the first SwiftUI frame to be composed after a successful process launch.
        time.sleep(4)
        run("io", device_id, "screenshot", str(output / "iPhone-home.png"))
        processes = run("spawn", device_id, "launchctl", "list")
        alive = any("com.ketvoice.ios" in line and line.split()[0].isdigit()
                    for line in processes.splitlines() if line.split())
        if not alive:
            raise RuntimeError("The iPhone app was not running after launch; inspect the simulator screenshot/logs")
        (output / "simulator-render.json").write_text(json.dumps({
            "device": preferred["name"], "runtime": runtime["version"],
            "launch": "passed", "process_alive_after_launch": True,
            "screenshot": "iPhone-home.png", "physical_device": False,
            "audio_validation": False, "navigation_validation": False,
        }, indent=2) + "\n", encoding="utf-8")
    finally:
        subprocess.run(["xcrun", "simctl", "shutdown", device_id], check=False, timeout=60)
        subprocess.run(["xcrun", "simctl", "delete", device_id], check=False, timeout=60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    render(args.app, args.output)
