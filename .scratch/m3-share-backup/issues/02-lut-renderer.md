# 02: .cube LUT 渲染管线（解析器 + GPUImage shader）

**What to build:**
- `CubeParser`：纯 Kotlin 解析 .cube（LUT_3D / LATTICE/NUMBER_COLORS / TITLE），坏档/1D/越界全拒；JVM 测试全路径
- 渲染：解析位 → Bitmap atlas（N³→N×N 块排布）→ 自定义 `GPUImageLut3DFilter`（two-input 纹理槽 + shader 三线性采样）；`GPUImage3x3ConvolutionFilter` 替换手写锐化
- 内置 4-6 个合成 .cube 预设（胶片暖/黑白/冷调/zine 风格系）+ SAF 导入任意 .cube 入库（Room 滤镜表）
- WorkRenderer 升 GL 路线：Pass 滤镜建议文本 → 选 LUT → 出 Work（2048px/q88 基线语义保留）

**Blocked by:** 无（与 01 并行可做）

**Status:** claimed（2026-11-08；主干落地；真机 BUG 一枚：shader varying 名 used vTextureCoord ≠ 基类 textureCoordinate → 成片全黑，已修，待用户复验 GL 出片）

- [ ] .cube 解析 JVM 测试全绿（好/坏/1D 拒收/维度异常）
- [ ] 真机：同一 JPEG 旧 ColorMatrix vs 新 LUT 出片对人眼可辨（像素差留证）
- [ ] 导入 .cube → 卡滤镜建议 → Work 出片全链
