# 07: 滤镜台（缩略图即预览 · 强度混合 · 手动选型）

**What to build:**
- 查看器底栏「滤镜」→ 全屏滤镜台：主画布实时预览 + 分类条 + ⊘ 无滤镜 + 缩略图横条（每条 = 源图套该滤镜的真渲染）
- 分类：全部 / 胶片 / 黑白 / 经典 / 风光；内置 12 支按题材归组。**「我的导入」留位但依赖 M3-02 的 .cube 导入 UI（那个入口还没做，空分类不渲染）**
- 强度滑杆 0–100%：LUT 结果与原图线性混合（shader 一个 uniform）；换滤镜沿用当前强度
- 保存 = WorkRendererGpu 渲染长边 2048 / q88 → 覆盖 `_w.jpg`，并让图库/查看器立刻看到新 Work
- 开屏默认选中：这张最近一次 AI 滤镜建议命中的那支（`filter_suggest` 取最新一条）

**Why:** 2026-11-15 用户给澎湃相册滤镜面板截图，点名"缩略图即预览、点即换"这个效果。现有路径只有 AI 自动挑一支就出片，没法手动试；`LutPresets` 的 12 支预设此前没有名称/题材，也没有任何地方能列出它们。

**Blocked by:** 无

**Status:** resolved（2026-11-15，双轴 review findings 已闭环）

- [x] `FilterCatalog`（纯 Kotlin：条目/分类/id 契约 + `FilterSelection`）+ 单测
- [x] `LutPresets.Preset` 补 name/category；`Lut3DFilter` 加强度 uniform
- [x] `FilterPreviewEngine`（GL 单线程持实例）出主画布与缩略图
- [x] 滤镜台 UI + 查看器入口（替换「滤镜」原自动跑建议的语义）
- [x] 保存覆盖 `_w.jpg` + 失效缩略图缓存 + 刷新图库（`GalleryItem.workRev` 让 produceState 重解码）
- [x] 单测全绿 + `assembleDebug` + 模拟器全链验证

## 证据（2026-11-15，模拟器 1080×2400）

- `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL；`FilterCatalogTest` 10 例全绿
- 用 `.ux-review/acc-result.jpg` 当 Master 塞进 `files/Master/2026-11-15/DSC_9001.JPG`：
  - 开屏＝源图；分类条「⊘ / 全部 / 胶片 / 黑白 / 经典 / 风光」，缩略条逐支渲染真效果（黑白那格确为灰阶）
  - 点「黑白胶片」→ 主画布转灰阶、选中格金色描边、底部出现强度滑杆
  - 拖 50% → 天空与外套颜色半回，读数 50%（`mix(c, lut, uStrength)`）
  - 点「保存」→ `files/Work/2026-11-15/DSC_9001_w.jpg` 380278B；logcat `work[gl]: ok=true in=1056x1760 render=1056x1760 out=380278B elapsed=121ms`
  - 关滤镜台 → 查看器显示半强度成片 + `Work ✓`
  - 二次保存（冷调山雾 50% → 胶片暖 39%）：查看器两次都立刻换成新成片（未修前第二次会停在旧图）
  - 换滤镜时强度保持（黑白 40% → 胶片暖 39%，不跳回 100%）
  - 切「胶片」档只剩 5 支胶片家族；连点四支不崩；保存后相机页标题仍是「未连接」（没被状态包冲掉）

## Review 闭环记录（2026-11-15）

Standards / Spec 双轴子代理并行审后的改动：
- 控制器并发：`suspend` 入口改走 `io.asCoroutineDispatcher()`，与 ingest/Pass 同一条线程，不再另开 `Dispatchers.IO`
- `FilterSelection(entry, rawStrength)` 收掉 `(entry, strength)` 数据泥团；`percent` 成为唯一取整口径（原先 UI 四舍五入、日志截断，会差 1%）
- 去掉投机泛化：`FilterCatalog.byId`、`FilterPreviewEngine.source`；`indexOfPreset` 归位到 `LutPresets.indexOf`
- `within` → `inCategory`（原名读起来像"包含关系"）；`Chip` → `CategoryChip`（别和 Compose 的 Chip 撞词）
- 保存定位由**文件名**改为 **relPath**：同名不同日期会命中错的那张 Master
- 保存后的状态发射改为 `lastEmitted.copy(galleryItems=…)`：原 `emit(ProbeUiState(...))` 会把相机页/其它页字段一起冲掉
- `WorkRendererGpu` 补长边 2048 的等比缩放：`ThumbDecode.sampled` 只做 2 的幂降采样，6000×4000 原本会按 24MP 出片
- 滤镜台根容器补指针目标：空心区域点击/拖动原本会漏到下面的 HorizontalPager（会换照片）
- 缩略图/LUT 失败时不再永久转圈（渲染失败保留上一帧并落 Logcat `Lumina.Filter`）

## 已知遗留（不在本票）

- 「我的导入」要等 M3-02 的 SAF .cube 导入 UI（`lut_library` 至今没有写入方）
- 「AI 挑一个」入口：`runFilterSuggestion` 现在没有 UI 调用方（滤镜台只读取既有建议），留在 M3-08
- 分享面板的预览缓存键（`share-$path-$mode`）在二次保存后不失效——分享页会显示旧图
- `_g.jpg` 存在时 `buildGalleryItems` 仍把 `workRelPath` 指到 `_g`（M4-01 优先级），此时保存的 `_w.jpg` 在图库里看不见
- 缩略图缓存键＝相对路径：图库格（256px）与查看器（屏宽）共用同一 key，互相覆盖（既有问题）
- `LutPresets.presetForCard` 的 zine 分支 `k.contains(family)` 里 family="极简 zine" 对任何单个 key 都 false → 该分支实际返回 null（潜在旧 bug，未改）
