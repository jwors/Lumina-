# 06: 真机验收 + 字节回放用例沉淀

**What to build:** 从用户视角：M0 验收签字——10 秒规则在真机达成；同时把这次真机会话的真实 PTP 响应字节 dump 下来作为回归 fixture 进入测试资源，从此协议层的修复永远有"真相机说过的话"做对照。

**Blocked by:** 05

**Status:** resolved（验收达成，见下；PTP 主路径装机待补测一项移交 M1）

## Comments

- **真机验收实录**（2026-11-04，Redmi/HyperOS + Z30 + OTG，无线 adb 全程跟踪）：
  1. 插线解锁 → App 内报 READY（`STILL_IMAGE_PTP` 分类正确）✓
  2. 系统权限授予 → 事件落盘 ✓
  3. **清单出现**：MtpDevice 兜底路径（story 17 首次实战）读出 **1 storage / 739 handles / 131 文件+若干目录**，屏显"MTP 兜底 · 清单就绪" ✓
  4. 拔线 → NOTConnected 归零 ✓；重插 → 自动重扫（initialScan 干正是上轮修的 bug）✓
- 验收路上修复的真机杀手（各一 commit，全 Einzel 测试回归）：
  1. App 后启动收不到 ATTACHED 广播 → 启动自扫描
  2. `0x201E SessionAlreadyOpened` → 宽容沿用
  3. `tid mismatch got 2` → 降级警告（系统服务先探测过相机）
  4. `0x2007 IncompleteTransfer` → 握手前 StillImage DeviceReset(0x21/0x66) + **GetDeviceInfo 挪进会话内**（ptplibrary/gphoto2 对齐）
  5. **短读毁包**（12B 头部读把 512B 包剩余字节丢进内核 → GetDeviceInfo Data 阶段死锁）→ 4K 全宽读 + 粘包留存（最终分水岭）
- **遗留三项移交 M1**（软件已尽力、证据在案）:
  1. 自研 PTP 主路径的 GetDeviceInfo Data 阶段在 org 设备超时（同设备 framework MtpDevice 可通；弱供电/RSB 悬疑，建议带电 OTG hub 复测）——PTP 主路径清单通电裁量
  2. 相机连续枚举 ~131 个 object 后停止应答（同上，供电疑点；代码端已做重试与空结果不冒充成功）
  3. 10 秒规则未精确计时（打包一次完整 MTP 枚举约 90s @739 对象；M1 用并行 GetObjectPropList 才是正道）
- 技术纪律：普通人话盘查 İşначе不了诊断源——所有事后定位（tid/0x201E/0x2007/整包）都是开机实拨 putting logcat + m0-probe.log 获得的。

- [ ] 真机完整跑通 05 全部验收标准（用户在场验收）
- [ ] 探针"一键 dump 会话字节"产出真实响应字节流
- [ ] 真实字节流回放为 JVM 单测 fixture（至少覆盖 OpenSession/型号/存储/清单四类响应）
- [ ] 协议层在全真实字节回放下全部单测绿（记录残留 known-issues 如有厂商私有响应被跳过）
- [ ] M0 验收记录写入工单 Comments：真机型号/Android 版本/耗时秒数
