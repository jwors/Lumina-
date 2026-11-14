# 08: 砍掉标注/文案（保留滤镜建议 + 分享）

**What to build（2026-11-15 用户拍板）：**
- 查看器底栏只留「滤镜」「分享」：删掉「标注」「文案」两个单步按钮，删掉「智能结果 · N」收合条
- 配方执行只跑「滤镜建议 / 生成式」：`PassExecutor` 跳过 ANNOTATE/CAPTION（StepKind 解析保留，旧导入卡不报错）
- 内置卡同步调整：`徒步日记·文案`、`锐利风光·标注`（纯标注/文案）下架；其余四张去掉标注/文案步骤并升版本
- 内置卡同步机制：下架行删除（连带清其队列行）、定义变更时保留原 cardId 刷新（队列引用不断）
- 查看器单步「滤镜」顺手修：挂云处理总开关、防连点、执行中提示随结果复位

**Out of scope:** 删除已有 smart_results 历史行（保留可查）；图库顶部「⚡ 智能结果全文」入口保留

**Status:** claimed（2026-11-15 实现完成，已装 `b6963b53` 待真机复验）

- [x] 查看器 UI 裁剪：只留「滤镜/分享」，删智能结果收合条
- [x] PassExecutor 跳过标注/文案；纯停用步骤的卡报失败
- [x] builtIns 重写 + syncPlan（下架/刷新/墓碑）与单测
- [x] 全量单测回归：`tests=224 failures=0 errors=0`（41 suites）
- [ ] 真机复验：查看器只剩滤镜/分享；旧卡执行只出滤镜结果；内置卡更新后步链正确

**证据（2026-11-15）：**
```
gradle -p E:/chyProject/Lumina :app:testDebugUnitTest --no-daemon
BUILD SUCCESSFUL ; suites=41 tests=224 failures=0 errors=0
  CardBuiltInsTest 5 · CardSeedTest 5 · PassExecutorTest 8（含停用步骤跳过/纯停用卡失败）
装 b6963b53：Success · lastUpdateTime=2026-11-15 00:44:39（APK 00:44:36）
```
