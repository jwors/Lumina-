# M3 · 分享 + 备份（图库工作台 / LUT 渲染 / 分享输出 / WebDAV）

> Status: ready-for-agent
> 上游：`docs/SPEC-Lumina.md` §3.5/3.6/3.7/§4/§7、ADR-0003、词汇 `GLOSSARY.md`（Master / Work / Processing Pass）
> grilling 结论：用户 8 问全部拍板（2026-11-08），见 Decisions

## Problem Statement

M2 后 Master 在手机里、智能结果可在，但成片走不出去：滤镜是 ColorMatrix 假渲染、图库还是 M1 时代 56dp 列表行（看不成片）、没有分享、没有备份。徒步拍的原片和一天的智能标注无处安放——手机丢失 = 全丢。

## Solution（M3 = 成片走向外部）

- **① 图库工作台**：网格缩略墙（3 列方格 + 日期分组 + LruCache 位图缓存）→ 点开全屏查看器（大图横滑 + 底部工具栏承接标注/文案/滤镜建议 + 智能结果收合条）；多选 = 长按进入 + 底部固定操作栏（相册类 App 习惯）；卡片化条目承"Work ✓ / 已覆盖"结果徽标
- **② .cube LUT 真渲染**：自研 .cube 解析器（纯 JVM 可测）→ GPUImage 自定义 shader 三线性插值；内置预设 + SAF 导入任意 .cube；WorkRenderer 从 ColorMatrix 假滤镜升级 GL 路线（Pass 建议文本 → 选 LUT → 出 Work）
- **③ 分享输出**：Work 两档规格 = 竖 4:5（社交，中心裁 + 4:5 窗口上下可拖）+ 原比例（存档）；分享屏 = Work 预览 + 智能配文 → 系统分享面板
- **④ WebDAV Master 备份**：手写轻客户端（OkHttp：PUT/MKCOL/PROPFIND）——坚果云/Alist/NAS 全覆盖；全 Master（JPEG+NEF）幂等上传（远端名单比对，传完即跳过，不分片续传）；上传成功后**询问清出手机**（保守删除：远端 PROPFIND 验证 + 字节大小比对 + 逐项清单确认）
- **⑤ 收尾**：真机全链验收（Master→卡→成片→分享→相册→云盘）+ M2-06 补验（10 张批量 + 断网补跑实录）+ CI 护栏上线

## Decisions（grilling 拍板，2026-11-08）

1. **Q1 范围**：图库工作台 = M3-01 放链路最前（分享/导出/滤镜全长在查看器上）；三小件（底部多选栏、saved 徽标、itemStatus 三态）顺手并入
2. **Q2 LUT 深度**：.cube 全量进 M3，**解析器自研**（事实核查：GPUImage 2.1.0 无 .cube 支持，字节级查证；解析纯 Kotlin 可 JVM 测，渲染走自定义 shader 三线性采样）
3. **Q3 分享规格**：4:5 + 原比例同出（4:5 主推）；4:5 = 中心裁 + 手动拖动窗口（不解析 LLM 构建标注做智能裁剪——那条进 M4 生成式一起想）
4. **Q4 WebDAV**：手写轻客户端（不上 sardine），PUT/MKCOL/PROPFIND 三操作；**全 Master（含 NEF）**；幂等 = 远端名单比对（天然断点：传完不再传；不做分片续传，半途断网整文件重来）
5. **Q5 清出手机**：询问弹层 M3 做；物理删除保守形态——远端 PROPFIND 存在性验证 + 字节大小一致性比对 + 逐项清单人工确认后才删（ADR-0003 明文"手动触发"）
6. **Q6 顺手项**：M2-06 补验并入真机验收票；CI（GitHub Actions 单测）本轮立起
7. **Q7 自研 PTP GetObject**：明确挂起 M4（与 PTP-IP 合并协议大票）；M3 用 MtpDevice.importFile 兜底不变
8. **Q8 图库视觉**：就地升级（现 F 色系 + 网格 + 圆角卡 + 日期浮标），F 稿全量重皮肤 = 可选尾票不阻塞主干

## Implementation Decisions

1. **.cube 解析**：格式规范=MAX/MIN/LUT_1D|LUT_3D/NUMBER_INPUT|COLORS（v1 只支持 3D LUT + PRIMARYGridLayout 常规格）；JVM 单测含坏档/非 3D 档/越界 lattice
2. **渲染管线**：解析产物 → androidx Bitmap atlas（N³→N×N 平面排布）→ 自定义 GPUImageFilter（two-input 纹理槽 + shader 坐标三次采样 + 手工 trilinear）；`GPUImage3x3ConvolutionFilter` 替换手写锐化卷积
3. **位图缓存**：`ThumbCache`（LruCache，目标 256px 级缩略 + 应用收尾清空）；查看器大图按屏宽 downsample 解码
4. **WebDAV 缝**：`WebDavClient` 接口（put/mkcol/propfind）+ `FakeDavClient`（JVM 真相）+ `OkHttpWebDavClient`（薄实装）；可选 testImplementation `mockwebserver:4.12.0`（与主 OkHttp 4.12.0 对齐）作集成档
5. **凭证**：WebDAV 地址/账号/密码存 Room（与 LLM 配置同款三元组风格，私有目录，不上云）

## Testing Decisions

- .cube 解析器：纯 JVM 全路径（好档/坏档/1D 拒收/维度异常/越界值）
- WebDavClient：Fake 档全语义（PUT 201/207/401/断连）+ 上传幂等（远端名单内跳过）+ 清出删除验证（Fake 验证过的才删）
- 渲染：ControlGroup 对拍——同一 JPEG 经 ColorMatrix 旧路 vs LUT 新路输出的位图像素差报告（JVM 不可测部分真机看片）
- 真机（05）：10 张批量 + 断网排队→回网补跑实录 + M2 遗留补验合并执行；NEF 上云计算备份耗时基线

## Out of Scope

- 自研 PTP GetObject 数据阶段 / PTP-IP（M4 协议大票）
- NEF 本地渲染（LibRaw 评估 = Phase 2；M3 只负责 NEF 备份上云）
- 分片续传 / 上传并发池（幂等整文件重试足够）
- F 稿视觉全量重皮肤（可选尾票，不阻塞验收）
- 生成式风格 / 智能裁剪线（M4，与生成式卡片一起拍板）

## Acceptance

真机：一次流程出片中——挑 1 张 JPEG Master → 卡执行 → LUT Work 成片 → 分享屏 4:5/原比例两档出图 → 系统分享面板可发；Master 库（含 NEF）WebDAV 上云成功（PROPFIND 名单核验）；断网配一批 → 回网横幅补跑一次；10 批量跑通即关 M2-06 补验与 M3-05。
