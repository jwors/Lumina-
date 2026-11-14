# 06: 图库删除（Master 级联清理）

**What to build:**
- 多选底栏新增「删除 N 张」：二次确认弹窗（张数 + 合计体积 + 不可恢复警示）后级联删除
- 全屏查看器 `⋯` 菜单新增「删除这张」：同款确认弹窗，**真正删掉后才关查看器**（失败留在大图并 toast 原因）
- 级联语义（2026-11-14 用户拍板）：Master 文件 + 盘点行 + 该片的 Work 成片（`_g.jpg`/`_w.jpg`）+ 智能结果 + 待处理队列 + 缩略图缓存一并清掉；队列清空后重算补跑横幅
- 同名/同 base 保护：仍有同名 Master 时不删名称键控的智能结果/队列；仍有同 base Master 时不删共享的 Work 文件名
- 删除按 `relPath` 定位；选中通道仍是文件名（同名跨日期会同时打勾），确认弹窗按可见条目显示真实张数

**Blocked by:** 01（图库工作台/查看器）、04（Master 盘点）

**Status:** resolved（2026-11-14 开工，2026-11-15 收尾 + 真机复验通过）

- [x] 删除链路：文件在即为准——文件删不掉就不删盘点行（否则下次 `loadGallery` 收养复活）——`MasterStore.deleteMaster` 三态 + 测例
- [x] JVM 单测：`MasterStore.deleteMaster` 三态（删成功/文件删不掉保行/失效行清理）+ `WorkFiles` 命名契约（含 onDeleted 缓存回调）+ `GalleryDeletePlan` 同名/同 base 保护
- [x] 全量单测回归：`tests=215 failures=0 errors=0`（40 suites）
- [x] 真机复验：多选删除 / 查看器删除 / 弹窗取消 / 删空图库回到空态——2026-11-15 装 `b6963b53` 后用户确认「功能没问题」

**证据（2026-11-15）：**
```
gradle -p E:/chyProject/Lumina :app:testDebugUnitTest --no-daemon
BUILD SUCCESSFUL ; suites=40 tests=215 failures=0 errors=0
  com.lumina.master.MasterStoreTest        12 tests（原 8 + deleteMaster 4）
  com.lumina.master.GalleryDeletePlanTest   5 tests（新增）
  com.lumina.work.WorkFilesTest             5 tests（新增）
```
> 本机沙箱把 `.gradle`/`app` 的写入挡住，实际命令行加了
> `-Pkotlin.project.persistent.dir=…/kotlin-build -Pkotlin.compiler.execution.strategy=in-process`
> 与 `--project-cache-dir` / `-Plumina.buildDir`；源码与测试语义不变。
