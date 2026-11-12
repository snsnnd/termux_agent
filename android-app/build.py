"""Build with Java 11+ and official Android SDK tools, without Gradle.

python build.py --tools /path/to/build-tools/34.0.0 --android /path/to/android.jar
Signing key stays outside the project; do not distribute it with the APK.
"""

import argparse
import os
import secrets
import subprocess
import zipfile
from pathlib import Path


def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tools", type=Path, required=True)
    parser.add_argument("--android", type=Path, required=True)
    parser.add_argument("--keys", type=Path,
                        default=Path.home() / ".local/share/termux-agent-bridge-signing")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    build = root / "build"
    classes = build / "classes"
    dex = build / "dex"
    generated = build / "generated"
    for path in (build, classes, dex, generated, args.keys):
        path.mkdir(parents=True, exist_ok=True)
    args.keys.chmod(0o700)
    tools, android = args.tools.resolve(), args.android.resolve()
    compiled = build / "resources.zip"
    run(tools / "aapt2", "compile", "--dir", root / "res", "-o", compiled)
    unsigned = build / "unsigned.apk"
    run(tools / "aapt2", "link", "-o", unsigned, "-I", android,
        "--manifest", root / "AndroidManifest.xml", "--java", generated, compiled)
    sources = sorted((root / "src").rglob("*.java")) + sorted(generated.rglob("*.java"))
    # Only our generated compiler output is removed, never source/user files.
    for stale in classes.rglob("*.class"):
        stale.unlink()
    run("javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-bootclasspath", str(android) + os.pathsep + str(tools / "core-lambda-stubs.jar"),
        "-d", classes, *sources)
    # A fresh jar ensures removed classes do not get added to the APK.
    jar = build / "classes.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes))
    run(tools / "d8", "--lib", android, "--min-api", "26", "--output", dex, jar)
    with zipfile.ZipFile(unsigned, "a") as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    aligned = build / "aligned.apk"
    run(tools / "zipalign", "-f", "4", unsigned, aligned)
    password_file = args.keys / "password"
    if not password_file.exists():
        password_file.write_text(secrets.token_hex(24))
        password_file.chmod(0o600)
    env = dict(os.environ, BRIDGE_STORE_PASSWORD=password_file.read_text().strip())
    key = args.keys / "bridge.p12"
    if not key.exists():
        run("keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
            "-alias", "bridge", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "3650", "-storepass:env", "BRIDGE_STORE_PASSWORD",
            "-dname", "CN=Local Termux Agent Bridge", env=env)
        key.chmod(0o600)
    apk = build / "agent-bridge.apk"
    run(tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "bridge",
        "--ks-pass", "env:BRIDGE_STORE_PASSWORD", "--out", apk, aligned, env=env)
    run(tools / "apksigner", "verify", "--verbose", apk)
    print(apk)


if __name__ == "__main__":
    main()
