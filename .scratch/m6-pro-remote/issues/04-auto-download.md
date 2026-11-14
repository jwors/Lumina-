# 04 · 拍后自动回传（JPEG）

> Status: ready-for-agent
> 上游：`.scratch/m6-pro-remote/spec.md`、01/02

## 范围

- 快门成功后：`GetObjectHandles` 找新 handle → `GetObjectInfo`/`GetObjectSize` → `GetObject`（或分段）下载 JPEG
- 存到 Master 库（复用 Ingest 的命名/入库）并 toast「已回传」
- 开关：自动回传 on/off；只传 JPEG，RAW 单独开关（后续）
- 失败不阻塞拍摄；日志记录 handle/大小/耗时

## 验收

- [ ] 单测：新 handle 识别、文件名/日期解析、下载缓冲
- [ ] 真机：按快门后手机出现该张 JPEG，与相机卡内文件字节一致
- [ ] 断线时提示且不破坏已拍照片
