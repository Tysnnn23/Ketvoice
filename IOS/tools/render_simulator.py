"""Render the built iPhone app on an isolated iOS simulator; never a physical-device check."""
import argparse
import json
from pathlib import Path
import subprocess
import time


def run(*arguments, timeout=120):
    return subprocess.check_output(["xcrun", "simctl", *arguments], text=True, timeout=timeout).strip()


def select_device(inventory):
    runtimes = [runtime for runtime in inventory["runtimes"]
                if runtime.get("isAvailable") and runtime["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]
    if not runtimes:
        raise RuntimeError("No iOS simulator runtime available")
    candidates = [(runtime, device) for runtime in runtimes
                  for device in inventory["devices"].get(runtime["identifier"], [])
                  if device.get("isAvailable") and device["name"].startswith("iPhone")]
    if not candidates: raise RuntimeError("No known-compatible iPhone/runtime pair available")
    runtime, device = max(candidates, key=lambda pair: (
        pair[1]["name"] == "iPhone 17", tuple(int(part) for part in pair[0]["version"].split("."))))
    preferred = next(value for value in inventory["devicetypes"] if value["name"] == device["name"])
    return preferred, runtime


def render(app: Path, output: Path):
    output.mkdir(parents=True, exist_ok=True)
    device_id = None
    stage = "inventory"
    try:
        inventory = json.loads(run("list", "--json"))
        preferred, runtime = select_device(inventory)
        stage = "create"
        device_id = run("create", "Ketvoice-CI", preferred["identifier"], runtime["identifier"])
        stage = "boot"
        run("boot", device_id)
        run("bootstatus", device_id, "-b", timeout=300)
        stage = "install"
        run("install", device_id, str(app))
        stage = "launch"
        run("launch", device_id, "com.ketvoice.ios")
        # Allow the first SwiftUI frame to be composed after a successful process launch.
        time.sleep(4)
        stage = "screenshot"
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
    except Exception as error:
        (output / "simulator-render.json").write_text(json.dumps({
            "launch": "not-validated", "failed_stage": stage, "exception": type(error).__name__,
            "physical_device": False, "audio_validation": False, "navigation_validation": False,
        }, indent=2) + "\n", encoding="utf-8")
        raise
    finally:
        if device_id:
            for action in ("shutdown", "delete"):
                try:
                    subprocess.run(["xcrun", "simctl", action, device_id], check=False, timeout=30)
                except subprocess.TimeoutExpired:
                    print(f"Simulator cleanup {action} timed out; the ephemeral runner will be disposed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    render(args.app, args.output)
