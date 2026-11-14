# M6-06 Spike · 云创 Picture Control 导入

> Status: resolved（结论：**本地 614 / NP3 导入可行**；**在线云创抓取不做**）
> 日期：2026-11-14
> 上游：`.scratch/m6-pro-remote/spec.md`、`.scratch/m6-pro-remote/issues/06-picture-control.md`

## 问题

能否把尼康「云创」配方（Picture Control）写进 Z30 的 C1–C9 自定义槽位？分两问：

1. 相机侧协议是否开放、能否稳定写入？
2. 配方数据从哪里来（本地文件 / 云端 API）？

## 证据

### 相机侧协议（gphoto2 `ptp.h`）

| OpCode | 名称 | 参数 | 数据 |
|---|---|---|---|
| `0x90CC` | GetPictCtrlData | 2 个 `(slot, 0)` | Data-In |
| `0x90CD` | SetPictCtrlData | 2 个 `(slot, 0)` | Data-Out |
| `0x90CE` | DelCstPicCtrl | 1 个 | —（**破坏性**） |
| `0x90CF` | GetPicCtrlCapability | 1 个 | Data-In |

这三个码在 libgphoto2 的 [ptp.h](https://raw.githubusercontent.com/gphoto/libgphoto2/master/camlibs/ptp2/ptp.h) 有定义；Z30 槽位号是 `0x00C9–0x00D1`（9 个）。

### 614 字节载荷（真机实测口径）

来自 [z-cloud-ai](https://github.com/laoziocean-code/z-cloud-ai)（MIT，Android/Kotlin）的真机记录：

| 偏移 | 长度 | 内容 |
|---|---|---|
| `+0x000` | 2 | 版本 `02 00` |
| `+0x002` | 20 | ASCII 名称（NUL 填充） |
| `+0x019` | 1 | 控制点个数（`0x08`） |
| `+0x02C` | 1 | 日/时标记（可沿用） |
| `+0x02F` | 8 | 4 组 `(x, y)` 控制点 |
| `+0x066` | **512** | **256 × u16 大端 LUT，值域 0..32767** |

`0x66 + 512 = 0x266 = 614`。LUT 是成片真实生效的部分；控制点只给相机 LCD 画图用。

### 关键负面结果（[1dlut-z30-filters](https://github.com/haotiandi1011-lgtm/1dlut-z30-filters)）

- Z30 自带 9 个自定义槽位；官方只支持从存储卡导入，菜单里没有曲线编辑器。
- 三种 NP3 构造方式在真机都「导入成功」，但**读回只剩 36 字节，曲线被固件截断** → 不能只信「导入成功」。
- `0x90CE` 删槽是破坏性的，该项目明确不调用。
- 只验证过 Z30 固件 **C 1.20**；其他版本要重新标定。

### NP3 文件格式（[ssssota/nikon-flexible-color-picture-control](https://github.com/ssssota/nikon-flexible-color-picture-control)，MIT）

NP3 字段：name / comment / contrast / toneCurve（257 点 + 控制点）/ sharpening / mid-range sharpening / clarity / highlights / shadows / white level / black level / saturation / color blender / color grading。可在本地解析成 614 载荷。

## 结论

1. **本地导入：可行，建议做。**
   - 读槽备份（0x90CC）→ 构造 614 字节 → 写槽（0x90CD）→ **回读 512B LUT 校验**。
   - 只写 C1–C9，**永不调用 0x90CE**。
   - 入口只接用户本地的 `.np3` / `.cube`，不内置任何厂商配方数据。
2. **在线云创抓取：不做。**
   - 尼康 Imaging Cloud 的配方是账号/App 绑定，没有公开 API；抓取有 ToS 与鉴权风险。
   - 若以后要做，也只做「用户自己从官方 App 导出的文件」导入。
3. **风险**：固件 C1.20 之外的槽位映射、NP3 曲线被截断、0x90CD 参数校验，都必须真机 probe；导入后必须回读校验，不能只看成功响应。

## 下一步（另开工单，不在本 spike 内）

- `PcPayload614` 构造/解析（名称 + 256 点 LUT，大端）。
- `PtpSession.getPicCtrlData / setPicCtrlData / getPicCtrlCapability`。
- SAF 选 `.np3` → 预览曲线 → 选槽 → 写 → 回读比对。
- 真机：先读 C1 原始载荷落盘，再写一个线性 LUT，确认 LCD/成片变化。
