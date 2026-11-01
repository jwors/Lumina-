# 03: 对象清单遍历 + 日期分组 + 会话日志

**What to build:** 从用户视角（未来 M1 的地基）：给一次 PTP 会话，协议层能遍历存储里的对象，返回"结构化清单（句柄/文件名/体积/拍摄日期/父目录）+ 总数/总体积 + 按日期分组的视图数据"，同时维护最近 N 行环形会话日志供探针显示。仍是纯 JVM 可验证。

**Blocked by:** 02

**Status:** resolved

## Comments

- 线格式事实经权威源修正（gphoto2 `ptp_unpack_OI` + Canon 真机 ObjectInfo 示例）：ObjectInfo 数据集固定段末尾**含 ParentObject u32 与 AssociationType u16**，层级与目录判定直接来自数据集（无需逐父下钻）；日期是 **PTP TIME 字符串**（"YYYYMMDDTHHMMSS"，此前误存 u32 无法表示现代日期）。
- 实现：`ObjectInfo.kt`（真实线格式解析 + PTP TIME → epoch 换算，minSdk 26 用 java.time）、`ObjectCatalog.kt`（GetObjectHandles(PARENT_ALL) 扁平枚举 → 逐句柄 GetObjectInfo → visited 去重 + 根句柄归一化 + 按拍摄日期/修改日期回退分组汇总）、`RingLog.kt`（容量 200 环形日志，接进 PtpSession 每事务请求/响应两行）、`PtpSession.kt` 增 GetObjectHandles/GetObjectInfo 事务与对象数上限防御（2^20，防谎报计数导致分配爆炸）。
- 验收 7 例全绿（`gradle :app:testDebugUnitTest` BUILD SUCCESSFUL，21/21）：枚举请求字节形状（params=storage/format/0xFFFFFFFF）、清单含目录/文件/体积/父子层级/日期、u32 体积无符号读出（>4GB MP4）、日期分组从新到旧、空存储、谎报超大计数拒绝、重复句柄去重（脚本刻意不提供第二次响应）、环形日志只留最近 3 行。
- 真机未知数挂 05/06 号校验：Samsung 64 位 ObjectSize 变体、ParentObject 根值口径（0x00000000 vs 0xFFFFFFFF）、PTP TIME 是否带时区/分数秒、长文件名。

- [x] GetObjectInfo 遍历解析：文件名（含长名）、体积、Capture Date、父目录层级
- [x] 父目录遍历终止条件正确：设计改为 ParentObject/AssociationType 字段建树（扁平枚举，gphoto2/真机口径），终止性由句柄唯一 + visited 集合保证；真机口径挂 05/06 校验
- [x] 清单聚合：总数、总体积、按拍摄日期分组的视图数据
- [x] 环形缓冲日志（最近 ~200 行），向外暴露快照读取
- [x] 单测覆盖边界：零文件存储、超大对象计数、NEF/JPG/视频混合 + 超长文件名（原"非暧昧扩展名"按混合格式处理）

## Code Review (2026-11-02) 处理记录

- Standards：测试字节构建器抽共享 fixture `FakeCamera.kt`（原两份已漂移：字符串终结符不一致，其一不符合 PIMA NUL 约定）；死常量 GET_PARTIAL_OBJECT / RespCode 冗余 / RingLog.clear() 删除。
- Standards-c1 真修复：tid 改为事务发起前预递增——失败事务不重用 tid（新增回归测试）。
- Spec-c3 补对称：readData 响应先到的错误路径也校验 tid。
- Spec-a3 补日志：错误响应行（ERR）也进环形日志（新增回归测试）。
- Standards-c2 / Spec-b 补防御：容器长度声明上限 256 MiB、DeviceInfo 代码数组上限 65536、StorageIDs 上限 255（新增回归测试 ×2）。
- 最终验收 9 例 + 全套 25/25 绿（`gradle :app:testDebugUnitTest` BUILD SUCCESSFUL）。
