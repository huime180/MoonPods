#!/usr/bin/env python3
"""从水月雨公开云端抓取「所有机型」的能力素材。

与模块运行时无关：只读公开接口，产出 capabilities.json（机型元数据 + 频响文件地址），
可选把频响文件下载到 tools/out/capabilities/ 供离线分析。

用法:
    python3 tools/harvest_capabilities.py            # 只生成 JSON
    python3 tools/harvest_capabilities.py --download # 同时下载频响文件
"""
import json, os, sys, urllib.request, urllib.parse

BASE = "https://cdn-service.moondroplab.tech/api/v1/"
CDN = "https://cdn.moondroplab.tech/"
OUT = os.path.join(os.path.dirname(__file__), "out")


def get_json(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        return json.load(r)


def main():
    download = "--download" in sys.argv
    os.makedirs(OUT, exist_ok=True)
    products = get_json("products/all")["data"]
    out = []
    for p in products:
        root = (p.get("appResourceRoot") or {}).get("uri")
        out.append({
            "name": p.get("name") or p.get("model"),
            "uuid": p.get("uuid"),
            "type": p.get("type"),
            "chip": (p.get("chipType") or (root or "").strip("/") or None),
            "chipRoot": root,
            "isTws": bool(p.get("istws")),
            "eqBands": p.get("eqBands") or 0,
            "qRange": p.get("qRange"),
            "gainRange": p.get("gainRange"),
            "isPreGainSupported": bool(p.get("isPreGainSupported")),
            "isTotalGainSupported": bool(p.get("isTotalGainSupported")),
            "supportedFilterTypes": p.get("supportedFilterTypes"),
            "freqResponse": p.get("freqResponse"),
            "manual": p.get("manual"),
            "hasOtaConfig": bool(p.get("hasotaconfig")),
        })
    cfg = os.path.join(OUT, "capabilities.json")
    with open(cfg, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    bt = [x for x in out if (x["type"] or "").upper() == "BT"]
    with_fr = [x for x in bt if x["freqResponse"]]
    print(f"products={len(out)} bt={len(bt)} with_freq_response={len(with_fr)}")
    print(f"written: {cfg}")

    if download:
        d = os.path.join(OUT, "capabilities")
        os.makedirs(d, exist_ok=True)
        ok = fail = skip = 0
        for x in with_fr:
            key = (x["name"] or "unknown").lower().replace(" ", "_")
            dst = os.path.join(d, key + ".txt")
            if os.path.exists(dst) and os.path.getsize(dst) > 0:
                skip += 1
                continue
            try:
                url = CDN + urllib.parse.quote(x["freqResponse"], safe="/")
                with urllib.request.urlopen(url, timeout=30) as r:
                    data = r.read()
                open(dst, "wb").write(data)
                ok += 1
            except Exception as e:
                fail += 1
                print("  fail", key, e)
        print(f"freq files: downloaded={ok} skipped={skip} failed={fail}")


if __name__ == "__main__":
    main()
