<div align="center">

# MoonPods

**为 HyperOS 设备提供水月雨（MOONDROP）耳机的系统级控制**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-澎湃OS-orange?style=flat-square)](https://hyperos.mi.com)

</div>

MoonPods 接管水月雨蓝牙耳机：识别机型、按设备自报能力加载控制项，并提供两种工作模式 ——
**Root（LSPosed 注入系统蓝牙进程）** 与 **免 root（应用进程直连耳机）**。

同一套协议实现、同一份云端机型目录、同一套 UI，两种模式的差别只在「谁持有蓝牙链路、
谁负责发通知与弹窗」。

## 两种模式对比

| 能力 | Root（Hook） | 免 root（应用直连） |
|---|---|---|
| 蓝牙链路 | `com.android.bluetooth` 进程 | 应用进程 |
| 机型识别 | 云端目录 + 官方 App 缓存 | 同左 |
| ANC / 电量 / 增益 / 指示灯 / 提示音 / LHDC / 双设备 / 手势 | ✅ | ✅ |
| 连接弹窗 | ✅ 模块弹窗 / ✅ 系统弹窗 | ✅ 模块弹窗 / ❌ 系统弹窗 |
| 超级岛 | ✅ 官方 / ✅ 模块 | ✅ 模块（官方需系统应用发通知） |
| 焦点通知 | ✅ 小米蓝牙进程发布 | ✅ 应用发布（同款文案与机型图） |
| 融合设备中心卡片 | ✅ | ❌ |
| 系统蓝牙设置页适配 | ✅ | ❌ |
| 连接时自动挂载 | ✅ A2DP 事件 + 已连设备扫描 | ✅ 广播 + ACL 兜底 + 启动自检 |
| 依赖 | LSPosed，作用域四进程 | 仅运行时权限 |
| 通知开关 | ✅ 受「焦点通知」控制 | ✅ 受「焦点通知」控制 |

**切换模式**会自动完成：断开耳机 → 撤销通知 → 写入配置 → 重启作用域 → 重启应用。

## 功能

**耳机控制**

- 降噪档位：主档 通透 / 降噪 / 关闭，子档 基本 / 抗风噪 / 自适应，按设备能力探测生成
- 三路电量（含单设备电量机型），离线显示为一条短横线
- 增益、指示灯、提示音开关与音量
- LHDC 与双设备连接（互斥处理）
- 手势控制（TOUCHV2，5 个槽位 × 双耳）

**系统集成（Root）**

- 超级岛（官方模板 / 模块自建）
- 融合设备中心卡片，档位按水月雨下标回传
- 系统蓝牙设置页的耳机详情适配

**应用**

- 设备页 / 设置页 / 快捷弹窗
- 通知固定文案：`左 100%`，离线项不显示
- 机型图（可导入官方渲染图，按单耳/双耳自动切换）

## 数据来源

1. **云端机型目录**（`products/all`）：型号名、产品 UUID、芯片族 → 决定识别与传输线路；
2. **官方 App 本地缓存**（`cachedDeviceFunc_<uuid>_<固件版本>`）：该机型的功能清单，
   由应用进程读取并写入共享缓存，两种模式共用；
3. **运行时能力探测**：连接后由设备自报 feature 集合，作为最终权威；
4. **内置实测档案**：布丁、梦回2、太空漫游2、U.C.T.S 的档位与增益语义来自真机实测，
   不依赖探测（见 `MoondropModelRegistry.calibrationFor`）。

## 安装

1. 安装 APK；
2. Root 模式：LSPosed 启用模块，作用域勾选四个：
   `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service`、`com.android.settings`；
3. 一键重启作用域（或重启手机）；
4. 设置里选择工作模式，连接耳机。

免 root 模式只需授予：蓝牙连接/扫描（Android 12+）、通知（Android 13+），
以及**显示在其他应用上层**（连接弹窗需要）。

## 已知限制

- 免 root 无法使用系统弹窗与融合设备中心 —— 它们由系统进程提供；
- 免 root 的超级岛取决于 MIUI 对第三方焦点通知的白名单，表现可能不如 Root 稳定；
- 云端目录只提供元数据，功能能力以设备自报为准，未连接过的机型无法预知全部能力。

## 致谢

- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) — 原始项目
- [1812z/OppoPods](https://github.com/1812z/OppoPods) — 底栏结构与作用域重启
- [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) — 水月雨协议逆向与实测数据
- [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) — GAIA V3 命令目录
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose 组件

## 许可证

GPL-3.0
