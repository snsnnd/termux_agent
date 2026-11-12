"""After build.py: python3 tests/run_efficiency.py --android /path/to/android.jar

Downloads checksum-pinned org.json for JVM tests only, never packaged in the App.
No API credentials, live model calls, or device changes.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--android", type=Path, required=True)
parser.add_argument("--cache", type=Path, default=Path("/tmp/opencode/pocket-agent-test-deps"))
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
args.cache.mkdir(parents=True, exist_ok=True)
jar = args.cache / "json-20240303.jar"
expected = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
if not jar.exists():
    with urllib.request.urlopen("https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar", timeout=30) as response:
        jar.write_bytes(response.read())
if hashlib.sha256(jar.read_bytes()).hexdigest() != expected:
    raise SystemExit("Test dependency checksum mismatch")
classes = root / "build" / "classes"
if not (classes / "local/termux/agentbridge/VisualSession.class").exists():
    raise SystemExit("Run build.py first")
out = root / "build" / "test-classes"
out.mkdir(parents=True, exist_ok=True)
cp = os.pathsep.join(map(str, [jar, classes, args.android.resolve()]))
subprocess.run(["javac", "-encoding", "UTF-8", "-cp", cp, "-d", str(out), str(root / "tests/EfficiencyTest.java"), str(root / "tests/PolicyTest.java")], check=True)
for test in ("PolicyTest", "EfficiencyTest"):
    subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "local.termux.agentbridge." + test], check=True)
