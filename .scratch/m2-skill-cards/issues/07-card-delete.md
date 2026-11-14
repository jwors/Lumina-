# 07: 配方删除

**What to build:**
- 配方卡片行新增「删除」入口：二次确认弹窗（卡名 + 说明）后永久删除
- 全部配方可删：内置示例卡删除后记墓碑（按名字），`loadCards` 的「缺哪补哪」播种不再把它补回来
- 级联（2026-11-15 用户拍板）：清 `processing_queue` 里该 `cardId` 的待处理行并刷新补跑横幅；已产出的智能结果/Work 保留
- 保护：该卡正在执行（`CardRunUi.phase == RUNNING`）时拒绝删除并提示先停
- 配方删空后页面要真的显示空态（`cards` 空列表不得被发射合并吞掉）

**Blocked by:** 无（M2 卡库已上线）

**Status:** claimed（2026-11-15 实现完成，已装 `b6963b53` 待真机复验）

- [x] 卡库空列表专用发射（`publishCards` 直接翻转，删空显示空态）
- [x] 内置卡墓碑（SharedPreferences 按名字；重装/清数据才恢复）
- [x] JVM 单测：播种过滤（已有名/墓碑名不补，新增内置仍补）
- [x] 全量单测回归：`tests=218 failures=0 errors=0`（41 suites）
- [ ] 真机复验：删导入卡 / 删内置卡后重开不复活 / 删空空态 / 二次确认可取消

**证据（2026-11-15）：**
```
gradle -p E:/chyProject/Lumina :app:testDebugUnitTest --no-daemon
BUILD SUCCESSFUL ; suites=41 tests=218 failures=0 errors=0
  com.lumina.cards.CardSeedTest  3 tests（新增）
装 b6963b53：Success · lastUpdateTime=2026-11-15 00:34:32（APK 00:34:28）
```
