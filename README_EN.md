<div align="center">

# MoonPods

**System-level control of MOONDROP earbuds on HyperOS**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS-orange?style=flat-square)](https://hyperos.mi.com)

</div>

MoonPods identifies MOONDROP earbuds, loads controls from what the device actually
reports, and offers two mutually exclusive work modes: **root** (LSPosed hooks the
system Bluetooth process) and **no-root** (the app owns the link itself).

The protocol, the cloud model catalogue and the UI are shared; only the owner of
the Bluetooth link and of the notifications differs.

## Mode comparison

| Capability | Root (hook) | No-root (app) |
|---|---|---|
| Bluetooth link owner | `com.android.bluetooth` | the app process |
| Model identification | cloud catalogue + official app cache | same |
| ANC / battery / gain / LED / prompt tone / LHDC / dual connection / gestures | ✅ | ✅ |
| Connect popup | module / system | module only |
| Super island | official / module | module |
| Focus notification | posted by Xiaomi Bluetooth | posted by the app |
| Fusion device card & Bluetooth settings integration | ✅ | ❌ |
| Auto-attach on connect | A2DP events + scan of connected devices | broadcasts + ACL fallback + start-up check |
| Requirements | LSPosed, four scopes | runtime permissions only |

Switching modes performs the whole sequence: release the earbuds, cancel the
notifications, persist the mode, restart the scope and restart the app.

## Feature highlights

- Noise control with device-probed paths (transparency / ANC / off, plus basic,
  anti-wind and adaptive sub-modes)
- Three battery slots; an offline slot renders as a single dash
- Gain, LED, prompt tone and volume
- LHDC and dual connection with mutual exclusion
- Gesture control (TOUCHV2, five slots per earbud)
- Device/notification artwork, per-ear switching

## Where capability data comes from

1. cloud catalogue (`products/all`): model name, product UUID, chip family;
2. the official app's local cache (`cachedDeviceFunc_<uuid>_<firmware>`): per-model
   feature list, read once by the app and shared with the hooked processes;
3. runtime probing: the device's own reported feature set is authoritative.

## Install

1. Install the APK.
2. Root mode: enable the module in LSPosed with scopes `com.android.bluetooth`,
   `com.xiaomi.bluetooth`, `com.milink.service`, `com.android.settings`, then restart
   the scope.
3. No-root mode: grant Bluetooth connect/scan (Android 12+) and notifications
   (Android 13+); the module popup additionally needs "display over other apps".

## Known limitations

- No-root cannot provide the system popup or the fusion device card;
- super island on no-root depends on MIUI's third-party whitelist;
- the catalogue carries metadata only; features of a model that has never been
  connected can only be probed, not predicted.

## License

GPL-3.0
