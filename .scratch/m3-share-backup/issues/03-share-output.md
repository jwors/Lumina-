# 03: 分享输出（4:5 zine 海报化排版 + 两档 + 分享屏）

**What to build（2026-11-09 用户拍板升级：本地把"zine 海报排版层"提进 M3）：**
- **海报化排版层 `ZinePosterRenderer`**：Canvas 本地合成——结构 Mantle：字标题带（吃 caption 智能结果首条）+ 成片照片区（中心 4:5 裁）+ 底注带（dateKey/地名）+ 颗粒纹理覆盖 + 奶油底。生成式步（多章拼贴/网点纹理）仍归 M4，但单页 zine 海报感 v1 即可出
- 两档：4:5 zine 海报（裁窗偏移 Slider 微调）+ 原比例原图
- 分享屏：预览 + 配文（可编辑，预填 caption）→ FileProvider + 系统分享面板；另设"存相册"次级键
- Viewer 工具栏加"分享"入口（有 Work 成片的行才亮）

**Blocked by:** 01（查看器是入口）、02（LUT 成片为素材）

**Status:** claimed→主线完成（2026-11-09 用户复验通过： repell顶部文案带取消、照片顶满、缩放进相框、两档分享+存相册全部真机可用）
> 记三个事故：① drawBitmap 原尺寸粘贴溢出截断；② markdown 文案未净化（后随用户拍板整带取消）；③ **三轮 Success 拿旧 APK 反复安装**——`adb install` 前未 `assembleDebug`，装机纪律已立（AGENTS.md），版本号机制从此可肉眼验包。

- [x] 4:5 海报排版像素正确（字/照片/底注分区，颗粒覆盖）——顶部文案带取消后 photo 顶满（>80% 几何测试锁）
- [x] 两档切换 + 分享面板可达（FileProvider 权域正确）
- [x] 配文预填可编辑并进分享（EXTRA_TEXT 走 sanitize 净化）
- [x] 纯函数布局数学 JVM 测试（PosterLayoutTest 3 条）
