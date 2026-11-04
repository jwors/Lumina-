# M0 里程碑回顾（USB 连通性验证）

> 状态：**达成**（一处 PTP 主路径疑点带数据移交 M1）。日期：2026-11-04。

## 交付一览

| 票 | 内容 | 结果 |
|---|---|---|
| 01 | 工程骨架 + Transport 缝隙 + FakeTransport 契约测试 | ✅ 闭环（两次 code-review） |
| 02 | PTP 协议引擎（容器/MTP 数据集/会话状态机） | ✅ 8 验收测试 |
| 03 | 对象清单遍历 + 日期分组 + 环形日志 | ✅（真机线格式经 gphoto2 修正） |
| 04 | USB Transport + 三态状态机 + 广播接线 | ✅（纯逻辑 8 测） |
| 05 | 探针全链路集成（Compose 屏） | ✅（assembleDebug 通过） |
| 06 | 真机验收 | ✅ 达成（有限制细节见票） |

最终测试矩阵：**35/35 绿**（Manifest 1 / ObjectCatalog 8 / PtpSession 13 / FakeTransport 5 / UsbLogic 8）。

## 真机上拔出的 6 个软件 assassin（全部有 commit + 回归测试）

1. Android 13+ 动态 receiver 必须声明导出性（否则开屏即崩）
2. App 晚启动收不到 ATTACHED 广播 → 启动自扫描
3. `0x201E SessionAlreadyOpened`（粗暴拔线残留）→ 宽容沿用
4. tid 计数器被系统服务弄超前 → 宽容警告（gphoto2 语义）
5. `0x2007 IncompleteTransfer`（系统服务留下半截事务）→ StillImage DeviceReset(0x21/0x66) + GetDeviceInfo 进会话内
6. **USB bulk 短读毁包**（12B 头读丢掉 512B 包剩余 → Data 阶段死锁）→ 4K 全宽读 + 粘包留存

共同教训：FakeTransport 测的是字节流语义，跑不出 USB 包级语义——**真机问题必须真机分化**，但每一次都能靠 logcat + m0-probe.log 锁定（环境资产见 AGENTS.md / docs/DEVICE-TESTING.md）。

## 遗留（M1 接手）

- PTP 主路径 GetDeviceInfo Data 阶段在本机超时（framework MtpDevice 同时可通）——弱供电/线材疑点，建议带电 OTG hub 复测
- 相机连续枚举 ~131 对象后停止应答（同上；M1 的并行 GetObjectPropList 才是正道）
- 10 秒规则未计时（739 对象完整枚举 ~90s；M1 并行化 + 缩略图分置）

## 回溯整理出的仓库改进

- 新建 `AGENTS.md`（导航指针）+ `docs/DEVICE-TESTING.md`（无线 adb / 日志取证流程）
- 仓库级 git `http.proxy` 持久化（push 不再带参）
- **待决**：GitHub Actions 跑 `:app:testDebugUnitTest`（护栏），用户未拍板
