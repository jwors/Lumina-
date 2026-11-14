# 04 · 拍后自动回传（JPEG）

> Status: claimed（回传选择+下载+入库+开关完成；Z30 真机待验收）
> 上游：`.scratch/m6-pro-remote/spec.md`、01/02

## 范围

- 快门成功后：`GetObjectHandles` 找新 handle → `GetObjectInfo`/`GetObjectSize` → `GetObject` 下载 JPEG
- 存到 Master 库（复用 MasterStore 登记；同名冲突加序号，不覆盖已有 Master）
- 开关：自动回传 on/off；只传 JPEG，RAW 后续单独开关
- 失败不阻塞拍摄；日志记录 handle/大小/耗时

## 验收

- [x] 单测：新 handle 识别、JPEG 选择、下载缓冲（getObject）
- [ ] 真机：按快门后手机出现该张 JPEG，与相机卡内文件字节一致
- [ ] 断线时提示且不破坏已拍照片

## 真机日志（2026-11-14 15:33）

- 首次 `0x9207` OK（07:33:21），但 `GetObjectHandles(0xFFFFFFFF,0,0xFFFFFFFF)` 只回 **1 个根目录 handle**，新照片在 `DCIM/100NZ30` 子目录里 → 8 秒超时「未回传」。
- 已改为按 Z30 目录层级枚举：存储 → DCIM → 100NZ30 → 文件，取最大 JPEG handle。
- 第二次快门 `0x9207` 两次返回 `0x2019`（相机忙）；已加忙时重试一次。
- 回传仍未在真机验证成功，待重连后再按一次快门。

## 证据

- `GetObject(0x1009)` 数据入；`MAX_CONTAINER_BYTES` 提到 32MiB 容纳 JPEG。
- `AutoDownload.pickNewestJpeg` 跳过 NEF/目录取最大 handle；`AutoDownloadTest` 3 例。
- `PtpSession.getObject` 单测；`captureWithDownload` 先枚举 handle → 拍 → 8 秒内轮询新 JPEG → `getObject` → Master 入库。
- 相机页「拍后回传 JPEG 开/关」；入库同名加 `_1` 序号。
- 单测 190 全绿；APK 已装。
- **真机待验收**：Z30 是否支持 `storage=0xFFFFFFFF` 的 GetObjectHandles、JPEG 字节一致性。定时/间隔拍摄目前只按快门、不回传（避免连拍把 Wi-Fi 拖死）。
