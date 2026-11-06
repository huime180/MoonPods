#!/usr/bin/env python3
"""从官方 App（com.moondroplab.moondrop.moondrop_app）缓存中采集「机型 → 功能清单」。

需要 adb + root（设备已授权）。产出:
  tools/out/official_functions.json   uuid -> [功能 uri]
  tools/out/official-peq/*.txt       官方缓存的自定义 PEQ 参数
  tools/out/official-fr/*.txt        官方缓存的目标/原始频响曲线
"""
import json, os, re, subprocess, sys, tarfile, tempfile

PKG = "com.moondroplab.moondrop.moondrop_app"
OUT = os.path.join(os.path.dirname(__file__), "out")


def adb(args, timeout=120):
    return subprocess.run(["adb"] + args, capture_output=True, timeout=timeout)


def su_cat(path):
    p = adb(["shell", f"su -c 'cat {path}'"])
    return p.stdout.decode("utf-8", "replace")


def pull_dir(remote, local):
    os.makedirs(local, exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix=".tar") as tmp:
        adb(["shell", f"su -c 'tar -C {remote} -cf /data/local/tmp/_h.tar .'"])
        p = adb(["pull", "/data/local/tmp/_h.tar", tmp.name])
        if p.returncode != 0:
            return 0
        try:
            with tarfile.open(tmp.name) as t:
                t.extractall(local)
        except Exception:
            return 0
    return len([f for f in os.listdir(local) if f.endswith(".txt")])


def parse_functions(xml):
    best = {}
    for m in re.finditer(
        r"cachedDeviceFunc_([0-9a-fA-F-]{36})_([0-9.]+)\">(.*?)</string>",
        xml,
        re.S,
    ):
        uuid, ver, body = m.group(1), m.group(2), m.group(3)
        uris = re.findall(r"&quot;uri&quot;:&quot;([^&]+)&quot;", body)
        if not uris:
            continue
        v = [int(x) for x in ver.split(".") if x.isdigit()]
        if uuid not in best or v > best[uuid][0]:
            best[uuid] = (v, uris)
    return {k: v[1] for k, v in best.items()}


def main():
    os.makedirs(OUT, exist_ok=True)
    xml = su_cat(f"/data/data/{PKG}/shared_prefs/FlutterSharedPreferences.xml")
    funcs = parse_functions(xml)
    path = os.path.join(OUT, "official_functions.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(funcs, f, ensure_ascii=False, indent=2)
    print(f"official functions: {len(funcs)} products -> {path}")

    for sub, name in (("download-peq", "official-peq"), ("responseLib", "official-fr")):
        n = pull_dir(f"/data/data/{PKG}/files/{sub}", os.path.join(OUT, name))
        print(f"{name}: {n} files")


if __name__ == "__main__":
    main()
