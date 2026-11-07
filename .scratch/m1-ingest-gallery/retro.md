# M1 里程碑回顾（Ingest 复制 + Master 图库）

> 状态：**达成**（真机全链走通：勾选 → MTP 引擎复制 → Room 登记 → 图库呈现）。日期：2026-11-08。

## 交付一览

| 票 | 内容 | 结果 |
|---|---|---|
| 01 | Room 盘点 + Master 目录布局（ADR-0004） | ✅（8 JVM 测试，未引 Robolectric——记取舍） |
| 02 | IngestEngine 缝隙 + Fake + MtpImportEngine | ✅（7 测试，MtpDevice.importFile 引擎） |
| 03 | 相机页正式化（勾选/队列持久化/双 Tab） | ✅ |
| 04 | Master 图库（分组/缩略图/NEF 占位/reconcile） | ✅ |
| 05 | Ingest 进度 UI + 失败重试 | ✅ |
| 06 | 真机验收 | ✅（9 文件真实落盘实证 + 日期修复迁移） |
| 07 | BUG：切 Tab 空列表 | ✅（instrumentation 定案：发射台账缺失） |

最终测试矩阵：**50/50 绿**（Ingest 7 / Master 8 / Manifest 1 / ObjectCatalog 8 / PtpSession 13 / FakeTransport 5 / UsbLogic 8）。

## 真机大案（The Wall 与最终击破）

- 症状漫游：`0x2007 IncompleteTransfer` → tid 错位 → 超长沉默 → `+57687` 年份目录 → 切 Tab 列表空
- 学费换来的四个铁律：
  1. **PIMA 细则**：OpenSession tid=0；之后从 1 起
  2. **USB bulk 不能短读**（4K 全宽 stage + 粘包留存）
  3. **StillImage DeviceReset(0x21/0x66)** 是可靠 connect 的类层第序
  4. **所有 UI 状态必须过统一发射台账**（任何"直邮"都会击穿其他 Tab 的数据）
- GitHub 开源对照（用户牵线）：laheller/ptplibrary、michaelzoech/remoteyourcam-usb、StackOverflow 尼康 PTP 规范摘录

## 遗留（M2 接手）

- 自研 PTP GetObject 引擎（当前引擎=MtpDevice.importFile；主路径 Data 阶段深水区，带电 hub 复测）
- 断点续传 / 并行 GetObjectPropList（739 对象 ~90s 全枚举的优化）
- JPEG 缩略图实测补录（本轮全部勾 NEF）
- CI 护栏（GitHub Actions 单测）awaiting user 拍板

## 环境沉淀

- `AGENTS.md` + `docs/DEVICE-TESTING.md`（无线 adb / run-as 取证）
- 图库工具栏icon-only 评审记录：`docs/brand/ui-toolbar-variants.svg`
- 手绘 LOGO：`docs/brand/lumina-logo.svg`（白底黑字单色）
