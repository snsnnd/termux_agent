"""Private one-time ADB migration: no API keys or histories in APK/shared storage.

Requires an already-authorized ADB connection. The exported provider only accepts
the Android shell UID (2000). Run after installing the native app.
"""
import argparse
import base64
import json
import pathlib
import subprocess

EXPORT = r'''
import base64,json,pathlib,sqlite3,tarfile
home=pathlib.Path('/data/data/com.termux/files/home')
project=home/'termux-agent'
backup=home/'agent-backups/native-app-20261112-v1'
backup.mkdir(mode=0o700,parents=True,exist_ok=True);backup.chmod(0o700)
archive=backup/'termux-agent-before-native.tar.gz'
if not archive.exists():
    with tarfile.open(archive,'w:gz') as out: out.add(project,arcname='termux-agent')
    archive.chmod(0o600)
db=sqlite3.connect('file:'+str(project/'data/memory.db')+'?mode=ro',uri=True)
db.row_factory=sqlite3.Row
obj={'config':json.loads((project/'config.json').read_text())}
for name in ('messages','summaries','meta'):
    obj[name]=[dict(row) for row in db.execute('SELECT * FROM '+name)]
db.close()
files=[];total=0
workspace=project/'workspace'
for path in sorted(workspace.rglob('*')):
    rel=path.relative_to(workspace)
    if any(p in ('.snapshots','.agent_jobs','.git','__pycache__','node_modules') for p in rel.parts):continue
    if not path.is_file() or path.is_symlink():continue
    data=path.read_bytes();total+=len(data)
    if len(data)>4_000_000 or total>32_000_000:raise SystemExit('Workspace exceeds migration limits; inspect first')
    files.append({'path':str(rel),'data':base64.b64encode(data).decode()})
obj['workspace']=files
print(base64.b64encode(json.dumps(obj,ensure_ascii=True).encode()).decode())
'''


def export(adb):
    result = subprocess.run([
        adb, "shell", "-T", "run-as", "com.termux", "env",
        "HOME=/data/data/com.termux/files/home",
        "PATH=/data/data/com.termux/files/usr/bin:/system/bin",
        "/data/data/com.termux/files/usr/bin/python", "-",
    ], input=EXPORT.encode(), capture_output=True, check=True, timeout=90)
    encoded = b"".join(result.stdout.split())
    obj = json.loads(base64.b64decode(encoded, validate=True))
    print("Exported live Termux data:", len(obj["messages"]), "messages,",
          len(obj["summaries"]), "summaries,", len(obj["workspace"]), "workspace files")
    return encoded, obj


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--export-only", type=pathlib.Path)
    parser.add_argument("--import-file", type=pathlib.Path)
    args = parser.parse_args()
    if args.import_file:
        encoded = args.import_file.read_bytes()
    else:
        encoded, _ = export(args.adb)
    if args.export_only:
        args.export_only.write_bytes(encoded)
        args.export_only.chmod(0o600)
        print("Private temporary export saved with mode 600; delete after verified migration.")
        return
    subprocess.run([
        args.adb, "shell", "-T", "content", "write", "--uri",
        "content://local.termux.agentbridge.migration/import",
    ], input=encoded, check=True, timeout=90)
    print("Migration delivered directly to app private storage. No credentials printed.")


if __name__ == "__main__":
    main()
