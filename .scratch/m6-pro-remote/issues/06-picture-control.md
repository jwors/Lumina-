# 06 · 云创 Picture Control 导入（spike）

> Status: resolved（spike 结论：**本地 NP3/614 导入可行**；**在线云创抓取不做**）
> 上游：`.scratch/m6-pro-remote/spec.md`
> 报告：`.scratch/m6-pro-remote/spikes/picture-control.md`

## 范围

- 目标：把尼康云创配方（或本地 .NP3）转换后写入 Z30 的优化校准槽位
- 先做 spike：确认配方格式、相机侧写入属性/数据块、老机身兼容降级规则
- 如果格式封闭/需要云端鉴权，明确放弃并记录

## 结论

- 相机侧开放：`0x90CC GetPictCtrlData` / `0x90CD SetPictCtrlData` / `0x90CE DelCstPicCtrl`（破坏性）/ `0x90CF GetPicCtrlCapability`；槽位 `0x00C9–0x00D1`。
- 载荷是 **614 字节**：`+0x66` 起 512B = 256×u16 大端 LUT，值域 0..32767；其余是名称/控制点等。
- 只信 512B LUT；NP3 文件导入在 Z30 上有「曲线被截断」的负面记录，必须回读校验。
- 在线云创没有公开 API（账号/App 绑定），**不抓取**；只做本地文件导入。

## 验收

- [x] spike 结论（可行/不可行 + 证据）
- [ ] 导入一个配方并回读校验 —— **转为后续工单**（需真机 C1.20 槽位 probe）

## 证据

- 报告：`.scratch/m6-pro-remote/spikes/picture-control.md`（含 gphoto2 ptp.h、z-cloud-ai、1dlut 的结论与链接）。
- 代码：`PcPayload614` 构造/解析 + 5 例单测；`PtpSession.getPicCtrlData/setPicCtrlData/getPicCtrlCapability` + 1 例。
- 单测 201 全绿；APK 已装。真机导入本轮不做。
