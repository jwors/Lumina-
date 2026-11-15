# ADR-0007: Wi-Fi PTP-IP 只做拍后单张 JPEG 回传，不承担全量 Ingest

- **状态**: Accepted（2026-11-15，用户拍板 A 方案）
- **日期**: 2026-11-15
- **上游**: `docs/adr/0003`、`docs/adr/0006`、`docs/SPEC-Lumina.md` §3.1；`GLOSSARY.md`（Ingest / 遥控 / 拍后回传）

## Context（背景）

M6-04 已经用 Wi-Fi PTP-IP 的 `GetObject` 把快门后新产生的 JPEG 取回，并按 Master 登记。这与 ADR-0006 原句"本 ADR 不传输任何图像数据"以及 SPEC 的"照片只走 USB/PTP"产生字面冲突：需要明确 Wi-Fi 到底是不是第二条 Ingest 通道。

全卡 Wi-Fi 导入在技术上可行，但要补分段下载（NEF 超过当前 32MiB 缓冲）、断点续传、批量任务/取消、与 USB 已导入 Master 去重，以及速度/电量提示。Z30 真机首测一张 4.5MB JPEG 因枚举绕路耗时 **39.9 秒**（优化后待复测），不适合替代 USB 做全量导入。

## Decision（决策）

1. **全量 Ingest 只走 USB**：全卡枚举、按日期浏览、批量勾选、JPEG + NEF 都走 OTG/PTP。
2. **Wi-Fi PTP-IP 允许"拍后回传"**：遥控快门成功后，只取本次新产生的一张 JPEG，登记为 Master；它是即时可用副本，不是图库导入。
3. **Wi-Fi 不做**全卡浏览/批量下载/NEF 回传，也不做断点续传与分段大文件传输。要搬整卡仍插 OTG。
4. 文档中的"遥控不传图"统一改述为"遥控不承担全量取图"；BLE 链路仍然**绝不**传任何照片字节。

## Consequences（后果）

- 正面：保留 USB 的速度、稳定与 RAW 能力；Wi-Fi 让遥控拍完立刻能在手机看到这一张；权限与 UI 不膨胀。
- 负面：忘带 OTG 线时不能整卡备份；单张 JPEG 回传仍可能慢或超时（8 秒窗口），失败不阻塞拍摄。
- 未来若要做 Wi-Fi 全卡 Ingest（B 方案），需新 ADR 取代本 ADR，并补分段下载、断点续传、NEF 验证与速度/电量提示。

## 参考

- M6-04：`.scratch/m6-pro-remote/issues/04-auto-download.md`
- 代码：`RemoteSession.captureWithDownload()` / `ProbeController.saveDownloadedJpeg()`
