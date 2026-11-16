"""Smoke-tests the plugin jar on a real Paper server from the console.

    python3 -I scripts/smoke/run.py <server-dir> <scenario-file> [java]

<server-dir> holds paper.jar, eula.txt and plugins/Hawkeye.jar. Each scenario line is a console command,
optionally followed by " => <text>": the console output until the next command must contain that text.
The server is stopped after the last command; the exit status is 0 when every expectation held. The
full console log is written to <server-dir>/smoke.log.
"""
import glob
import os
import subprocess
import sys
import threading
import time

COMMAND_WAIT_SECONDS = 3
START_TIMEOUT_SECONDS = 300


def main():
    server_dir, scenario_file = sys.argv[1], sys.argv[2]
    java = sys.argv[3] if len(sys.argv) > 3 else (glob.glob(
        os.path.expanduser("~/.gradle/jdks/eclipse_adoptium-25*/bin/java")) or ["java"])[0]
    steps = []
    for line in open(scenario_file):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        command, _, expected = line.partition(" => ")
        steps.append((command.strip(), expected.strip()))

    process = subprocess.Popen([java, "-Xmx2G", "-jar", "paper.jar", "--nogui"], cwd=server_dir,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = []
    started = threading.Event()

    def read():
        for output in process.stdout:
            lines.append(output)
            if "Done (" in output:
                started.set()

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    failures = []
    if not started.wait(START_TIMEOUT_SECONDS):
        failures.append("the server did not start")
    else:
        time.sleep(8)
        for command, expected in steps:
            mark = len(lines)
            lines.append(">>> " + command + "\n")
            process.stdin.write(command + "\n")
            process.stdin.flush()
            time.sleep(COMMAND_WAIT_SECONDS)
            reply = "".join(lines[mark + 1:])
            if expected and expected not in reply:
                failures.append(f"{command!r}: expected {expected!r}")
        process.stdin.write("stop\n")
        process.stdin.flush()
    try:
        process.wait(120)
    except subprocess.TimeoutExpired:
        process.kill()
        failures.append("the server did not stop")
    reader.join(5)
    with open(os.path.join(server_dir, "smoke.log"), "w") as out:
        out.writelines(lines)
    errors = [line for line in lines if "[Hawkeye]" in line and ("ERROR" in line or "Exception" in line)]
    failures.extend("console: " + line.strip() for line in errors)
    for failure in failures:
        print("FAIL", failure)
    print("ok" if not failures else f"{len(failures)} failure(s)", f"({len(steps)} command(s))")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
