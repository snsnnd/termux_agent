"""Run Phase 1 pure-Java helper tests without the Android SDK.

python3 tests/run_helpers.py [--cache DIR]

Downloads checksum-pinned org.json for JVM tests only, never packaged in the App.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request

parser = argparse.ArgumentParser()
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

package = root / "src" / "local" / "termux" / "agentbridge"
sources = [
    package / "AgentPolicy.java",
    package / "CapabilitySet.java",
    package / "TaskSpec.java",
    package / "AuditRecord.java",
    package / "StopSummary.java",
    package / "AgentTask.java",
    root / "tests" / "Phase1Test.java",
]
out = root / "build" / "helper-classes"
out.mkdir(parents=True, exist_ok=True)
for stale in out.rglob("*.class"):
    stale.unlink()
cp = os.pathsep.join(map(str, [jar]))
subprocess.run(["javac", "-encoding", "UTF-8", "-cp", cp, "-d", str(out), *map(str, sources)], check=True)
subprocess.run(["java", "-cp", os.pathsep.join([str(out), cp]), "local.termux.agentbridge.Phase1Test"], check=True)
