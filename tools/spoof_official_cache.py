#!/usr/bin/env python3
"""向官方 App 的本地缓存「伪装」写入机型功能清单，然后由 harvest_official.py 采集。

原理：官方 App 的功能清单缓存键是
    flutter.cachedDeviceFunc_<产品UUID>_<固件版本>
值为 [{"uuid":"...","uri":"/jieli/peq"}, ...] 的 JSON 字符串（XML 里做 &quot; 转义）。

由于杰理芯片族的 uri 映射已通过布丁实测确认，本脚本把同一份映射套用到所有
杰理机型上，让官方 App「认为」它已经缓存过这些机型的能力，从而可以离线采集。
仅用于研究与自用，需 adb + root。
"""
import json, os, re, subprocess, sys, tempfile

PKG = "com.moondroplab.moondrop.moondrop_app"
PREFS = f"/data/data/{PKG}/shared_prefs/FlutterSharedPreferences.xml"
FW_VERSION = "3.5.2"
CACHE = os.path.join(os.path.dirname(__file__), "out", "capabilities.json")

# 布丁实测的杰理资源清单（来自官方 App 缓存）
JIELI_URIS = [
    "/jieli", "/jieli/ota", "/jieli/eq", "/jieli/peq", "/jieli/led",
    "/jieli/ancV2", "/jieli/prompttoneswitch", "/jieli/voicecontrol",
    "/jieli/voiceswitch", "/jieli/adaptiveanc", "/jieli/onebringtwoV2",
]


def adb(args, timeout=120):
    return subprocess.run(["adb"] + args, capture_output=True, timeout=timeout)


def read_prefs():
    p = adb(["shell", f"su -c 'cat {PREFS}'"])
    return p.stdout.decode("utf-8", "replace")


def write_prefs(xml):
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False, encoding="utf-8") as f:
        f.write(xml)
        local = f.name
    adb(["push", local, "/data/local/tmp/_prefs.xml"])
    adb(["shell", f"su -c 'cp /data/local/tmp/_prefs.xml {PREFS}.new && "
                   f"chown $(stat -c %u:%g {PREFS}) {PREFS}.new && "
                   f"chmod 660 {PREFS}.new && mv {PREFS}.new {PREFS}'"])
    os.unlink(local)


def main():
    models = json.load(open(CACHE, encoding="utf-8"))
    jieli = [m for m in models if (m.get("chip") or "").lower() == "jieli" and m.get("uuid")]
    if not jieli:
        print("no jieli models found")
        return
    xml = read_prefs()
    if not xml:
        print("cannot read official prefs (need root)")
        return
    payload = json.dumps([{"uuid": f"spoof-{i}", "uri": u} for i, u in enumerate(JIELI_URIS)])
    escaped = payload.replace('"', "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    inserted = 0
    for m in jieli:
        key = f"cachedDeviceFunc_{m['uuid']}_{FW_VERSION}"
        entry = f'    <string name="flutter.{key}">{escaped}</string>\n'
        if f"flutter.{key}" in xml:
            continue
        xml = xml.replace("</map>", entry + "</map>", 1)
        inserted += 1
    if inserted:
        write_prefs(xml)
    print(f"spoofed entries: {inserted} / {len(jieli)} jieli models")


if __name__ == "__main__":
    main()
