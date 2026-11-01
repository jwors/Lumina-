# Lumina 需求规格书

> 尼康 Z30 户外徒步场景的照片导出与 AI 加工 App。
> 本文档是 4 轮 grilling（24 个已决策问题）的正式共识记录，作为后续所有开发与 `grill-me-docs` 文档审计的事实基线。
> 领域词汇以根目录 `GLOSSARY.md` 为准；架构决策以 `docs/adr/0001~0003` 为准。

## 1. 产品定位

| 项 | 决定 |
|---|---|
| 产品性质 | **个人自用工具**（动机：尼康官方导出 App 体验差），不做账号体系，但配置持久化从第一天做对 |
| 用户画像 | 单人：中 Android 手机用户，徒步 + 摄影爱好者，相机尼康 Z30 |
| 不做什么 | iOS 版、多用户、服务器后端、第三方开发者插件 SDK、手机端本地大模型推理 |

## 2. 核心工作流（叙事）

户外徒步的完整闭环：

```
[无网宿营地] 插 OTG 线 → 识别 Z30 → 浏览卡内照片(按日期分组)
  → 勾选 → Ingest 纯本地复制到手机 (Master: NEF+JPEG)
  → 离线浏览 / 选片 / 挑 Skill Card 与 Filter → 排队
  ↓ [回到有网：酒店 WiFi / 火车 4G]
App 检测网络 → 自动补跑队列: LLM 标注/文案 → 智能选滤镜 → 生成式风格 Work
  → Work 写入系统相册 → 手机云相册自动备份
  → 手动触发 WebDAV 备份 → Master 清出手机（SD 卡 + 云端各一份）
```

## 3. 功能需求

### 3.1 连接与导出（Ingest）
- Android USB Host + OTG 线直连 Z30，PTP/MTP 协议；M0 里程碑先验证"发现相机→列出存储内容"
- 导出动作 = 复制 NEF + 相机 JPEG 成对到应用私有存储（Master），保留原文件名
- 按拍摄日期分组浏览卡内容；支持全选/勾选
- Wi-Fi PTP-IP 为 Phase 2；蓝牙批量导出**明确不做**（ADO ADR-0003）

### 3.2 图库（Master 浏览）
- 按日期分组，统计张数/体积
- JPEG 即时可览；NEF 封存不解析（Phase 1 以相机 JPEG 为处理源，RAW 高画质处理延 Phase 2 用 LibRaw 评估）
- 多选进入配方流程

### 3.3 大模型配置与智能层
- 统一 OpenAI 兼容格式：`base_url + api_key + model` 任意可配
- 预置平台模板：**DeepSeek**（`deepseek-v4-flash-vision-exp`，视觉实验性、图片≤384 token、Files API 免费复用，适合批量标注）、**智谱 GLM**（免费额度）、**Kimi**、OpenAI 通用槽
- 能力：a) 自动标注/标题/社媒文案生成；b) 智能选滤镜（模型返回 choice）；c) 生成式风格（Phase 2）
- 所有"看图"任务只调视觉模型。

### 3.4 Skill Card（处理配方）系统
- 本质：**可导入/导出的 JSON 处理配方**，声明步骤序列（智能标注节点 / 待选滤镜节点 / 生成式风格 prompt + 参数节点）
- 用户场景参考 "gc-minimal-zine-poster" 那类风格卡：处理后的成片有强烈个人风格
- Processing Pass = 照片 × Card 的一次执行；离线排队、回网补跑
- 内置若干示例卡；不做第三方代码插件

### 3.5 滤镜系统（Filter）
- .cube LUT；内置常用预设库 + 支持导入任意 .cube
- 非破坏性（ADR-0002）：Master 不变，滤镜可换可撤

### 3.6 备份（Master 归宿）
- Master：WebDAV 终点手动触发上传（坚果云/Alist/NAS），成功后询问是否清出手机（ADR-0003）
- Work/成品 JPEG：写入系统相册，交云相册自动同步

### 3.7 分享输出
- Work 默认两档规格：竖 4:5（朋友圈/小红书）+ 原比例档（存档），生成时可选
- 分享屏 = Work 预览 + 已生成的配文 → 系统分享面板

## 4. 体验与视觉

- **视觉方向：F · Grain 胶片奶绿**（选项池 ABCDEF 中敲定，参考 Figma Make "Photo-Import-App / Grain" 稿）
  - 森绿氛围底 + 奶油色卡面；衬线编辑感大标题 + 字母间距眉题；森绿胶囊 CTA；绿色圆勾选中态；底部四标签带指示条
- 硬性体验约束（一等需求）：单手大拇指可达（操作区下半屏）、大卡片少按钮（手套可操作）、离线全程可用、"有网补跑"无感
- UI 骨架 5 屏（叙事已确认）：连接屏 → Ingest 屏 → 图库屏 → 配方屏 → 分享屏

## 5. 隐私与计费 posture

- 云端处理 = App 直连模型 API，无自建服务器，按 token/张计费
- 设置页：云端处理总开关 + 明细日志（哪张 Master、用哪个模型、花费）
- 不做逐张确认打断（徒步返程一键跑完），总开关兜底

## 6. 技术选型（摘要，详 ADR）

| 层 | 选择 |
|---|---|
| 平台 | Android 8.0+，Kotlin + Jetpack Compose（ADR-0001） |
| 相机协议 | USB Host + PTP/MTP（M0 验证）；NP 二期 PTP-IP |
| 本地存储 | 应用私有目录 + Room（Master/Work/队列/卡片元数据） |
| 处理管线 | COIL + Android Bitmap/ColorMatrix for LUT 应用；生成式走云端 API |
| 网络 | Retrofit/OkHttp（OpenAI 格式）；sardine 或手写轻 WebDAV 客户端 |
| 出包 | 自用 sideload（APK 直装），无商店 |

## 7. 里程碑（顺序已确认）

| # | 里程碑 | 内容 | 验收 |
|---|---|---|---|
| M0 | 连通性验证 | USB 发现 Z30 → PTP 列出文件；OTG 线真机 trial | 真机上列出卡内照片数 |
| M1 | Ingest + 图库 | 选中→导入→按日期浏览 Master | 完整迁一次 Z30 卡到手机 |
| M2 | 智能层 | LLM 配置/标注/文案/选滤镜；Skill Card 引擎（容器、导入导出、Pass 队列） | 给 10 张片跑通一张卡 |
| M3 | 分享 + 备份 | Work 生成→系统相册→云相册；WebDAV Master 备份 | 全流程返回一个成片 + NEF 上云 |
| M4 | 生成式风格 + PTP-IP | 风格卡类型（zine-poster 类）+ Wi-Fi 直连 | 一张 Master → zine 风格 Work |

## 8. 已明确拒绝的方案（防止反复）

- ❌ 蓝牙批量导出（尼康 BLE 能力不足）
- ❌ Flutter/RN 跨平台（无跨端收益，桥接成本高）
- ❌ 手机本地大模型推理（算力/体验不可行）
- ❌ 逐张云端处理确认（打断徒步后的批量节奏）
- ❌ 自建服务器/后端（API 直连足够）
