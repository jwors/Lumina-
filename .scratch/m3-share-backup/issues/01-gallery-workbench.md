# 01: 图库工作台（网格墙 + 全屏查看器 + 多选底栏）

**What to build:**
- `LazyVerticalGrid` 3 列方格墙：日期分组 full-span 头行；方格 = 缩略位图（256px 级）+ Work ✓ 徽标 + ⚡ 智能角标 + NEF 胶片框占位
- `ThumbCache`：LruCache + decodeSampled（迁出 MainActivity 的双份 decode），进程内复用；查看器大图按屏宽 downsample
- 全屏查看器：HorizontalPager 横滑 + 顶栏文件名/关闭；底栏工具 = 标注/文案/滤镜（runSingleStep 既有链）+ 智能结果收合条 + Work 徽标；长按/勾选进入多选
- 多选底部固定操作栏：`取消 | 全选 | 用滤镜包处理 N 张 →`（沿用 passSelectedNames 通道）
- `CardRunUi.itemStatus` 三态 enum（评审遗留顺手项）

**Blocked by:** 无（M3 首）

**Status:** claimed（2026-11-08 开工）

- [ ] 网格墙分组滚动流畅（退出页不崩、旋转/内存压力不 OOM）
- [ ] 查看器大图清晰（≥ 屏宽）、左右滑 / 工具栏执行结果实时刷新
- [ ] 多选→底部栏→滤镜确认弹层全链沿用 M2 语义（N=0 禁用、NEF 提示）
- [ ] 单测：ThumbCache 命中/淘汰/尺寸上限；三态 enum 全路径
