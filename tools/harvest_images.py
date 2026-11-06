#!/usr/bin/env python3
"""下载云端各机型的产品图（用于机型图缺失时的回退）。

云端 products/all 里每台设备带多组图片字段：
  sellpic / squareBannerImg / bannerImg / bannerImgV2 …（夜间版带 Night 后缀）
本脚本按「机型键」下载第一张可用图，产出:
  app/src/main/assets/pod_images/<key>.png
  app/src/main/assets/pod_images/index.json   {key: filename}
"""
import json, os, urllib.request, urllib.parse

BASE = "https://cdn-service.moondroplab.tech/api/v1/products/all"
CDN = "https://cdn.moondroplab.tech/"
ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(ROOT, "app/src/main/assets/pod_images")
FIELDS = ["sellpic", "squareBannerImg", "bannerImgV2", "bannerImg", "squareBannerImgNight"]


def key_of(name):
    return "".join(c if c.isalnum() else "_" for c in (name or "").lower()).strip("_")


def main():
    os.makedirs(OUT, exist_ok=True)
    data = json.load(urllib.request.urlopen(BASE, timeout=30))["data"]
    index, ok, fail = {}, 0, 0
    for p in data:
        name = p.get("name") or p.get("model")
        if not name:
            continue
        rel = next((p[f] for f in FIELDS if p.get(f)), None)
        if not rel:
            continue
        key = key_of(name)
        dst = os.path.join(OUT, key + ".png")
        if os.path.exists(dst) and os.path.getsize(dst) > 0:
            index[key] = key + ".png"
            continue
        try:
            url = CDN + urllib.parse.quote(rel, safe="/")
            with urllib.request.urlopen(url, timeout=30) as r:
                blob = r.read()
            open(dst, "wb").write(blob)
            index[key] = key + ".png"
            ok += 1
        except Exception as e:
            fail += 1
            print("  fail", key, e)
    json.dump(index, open(os.path.join(OUT, "index.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"images: downloaded={ok} failed={fail} total={len(index)}")


if __name__ == "__main__":
    main()
