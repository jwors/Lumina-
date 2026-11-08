# M2 里程碑回顾（LLM 配置 + Skill Card 引擎 + 智能执行链）

> 状态：**达成**（智能数据层全链配置可控、卡片可执行、结果可回看；真机轻量走通 + 70 JVM 测试托底）。日期：2026-11-08。

## 交付一览

| 票 | 内容 | 结果 |
|---|---|---|
| 01 | LLM 配置层（三元组 + 模板 + 云处理总开关） | ✅ |
| 02 | LlmClient 缝隙（FakeLlmClient JVM 真相 / OkHttpLlmClient 薄实装） | ✅ |
| 03 | Skill Card v1 引擎（JSON 配方/解析校验/Room 卡库/SAF 导入导出） | ✅ |
| 04 | Processing Pass 执行器 + 补跑队列（串行/重试/取消缝） | ✅ |
| 05 | 图库智能层（⚡ 徽章/全文流/单步工具栏） | ✅ |
| 06 | 真机验收 | ✅（轻量收尾：2 张 JPEG 全链实证 + 相册导出；10 张批量与断网补跑降载遗留——用户拍板） |

最终测试矩阵：**70/70 绿**（Card 6 / Ingest 7 / Llm 6 / Master 8 / PassExecutor 6 / Manifest 3 / Catalog 8 / PtpSession 13 / FakeTransport 5 / Usb 8）。

## 本轮 UX 大改（会话驱动，非工单票）

- 从「执行 Pass（全部 Master）」裸链接 → 对标主流修图 App 三件套：
  1. **滤镜包卡片化**（封面 + 步链），点击开确认弹层（应用已选/全部，N=0 禁用）
  2. **半屏执行弹层**（整体进度 + 逐张 ✓/⏳/·/✗ + 取消缝），可收起后台继续
  3. **全局浮标**（`⏳ 卡名 (i/N)`，点回弹层；完成态"待保存"）
- 收尾 **Snapseed 式保存双分支**：另存 Work（推荐/无损）vs 覆盖 Master（谨慎/仅 JPEG/NEF 排除），动态 CTA `确认覆盖 N 张`
- 保存即写 MediaStore（Pictures/Lumina，API29+ 无需新增权限）——用户指认"两处都看不到图"的修复
- 交互原型可跑浏览器对照：`mockups/app-m2-replica.html`（v3，现代版）

## 评审与账目纪律（双轴 code-review 的战果）

- **GLOSSARY 硬违规两抓**：「原图/任务」禁用词全线清扫 → 文案与词汇表对齐
- **ADR-0002 冲突**：覆盖能力与"Master 永不修改"冲突 → 追加 2026-11-08 修订段（仅 JPEG、显式选择、NEF 强制另存、Work 先行落盘）——修的是文档缺口，不是代码
- **Spec 轴抓到真缺陷**：Run 阶段不产 Work 成片 → 修成 AWAIT_SAVE 前逐张渲染，"暂存为 Work 预览"从此为真
- PassExecutor 新增 `abortCheck` 缝 + JVM 测试（取消后余量留队列、结算只计已处理）

## 遗留（移交 M3 / 待补验）

- **06 号降载两项**：10 张 JPEG 批量全链复核 + 断网排队→回网横幅补跑实录 + 失败重跑验证
- 图库升级（用户已点名）：网格缩略墙 + 全屏查看器 + LruCache——现在仍是 M1 时代的 56dp 列表行
- 图库多选底部固定操作栏（代码保留旧顶部链接，mockup v3 已演）
- 图库行 `已另存 Work / 已覆盖` 结果徽标（需 MasterInventory 加列或扫盘判断；本轮只做了 Work ✓ 通用徽章）
- `CardRunUi.itemStatus` Map 缺键表三态 → 三态 enum 重构（评审记录在案）
- MediaStore 导出通道扩形态（M3 正式导出：尺寸/格式选项、NEF 预览通道）
- 自研 PTP GetObject 引擎（M1 移交，继续挂起）；CI 护栏仍 awaiting 用户拍板

## 环境沉淀

- 版本标记习惯确立：`0.2.0-m2ux`（versionCode 2），新旧包肉眼可辨
- 真机联调：24122RKC7C 经 USB adb 安装验证；HyperOS 拦截 adb 输入注入（tap 不可用，需"USB 调试（安全设置）"）；run-as 取证走 `exec-out cat`（PowerShell 管道会损伤二进制）
- App 内事件日志 + logcat 双通道确认口径：tag 是 `Lumina.UI`（不是 `Lumina`）
