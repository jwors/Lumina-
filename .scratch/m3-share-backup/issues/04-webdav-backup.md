# 04: WebDAV Master 备份 + 清出手机

**What to build:**
- `WebDavClient` 缝（put/mkcol/propfind）+ `FakeDavClient`（JVM 真相，套 FakeTransport 断言风格）+ `OkHttpWebDavClient`（薄实装，超时/重试一次）
- 配置：WebDAV 三元组（地址/账号/密码）入 Room + 设置页表单 + 测试连接（PROPFIND 根目录）
- 上传：全 Master（JPEG+NEF）幂等（远端名单比对跳过已传）；工作目录 `Lumina/Master/<date>/`；失败整文件重来（不分片）
- 清出（ADR-0003）：上传完成 → PROPFIND 存在性 + 字节大小比对 → 询问弹层逐项清单 → 确认后删本地文件 + 盘点行（pruneAbsent 收敛）

**Blocked by:** 01（图库是入口）, 02（成片先立）

**Status:** ready-for-agent

- [ ] Fake 档全语义测试（PUT 201/401、PROPFIND 名单、幂等跳过、删除守门）
- [ ] 真机：坚果云或 Alist 任一账号全 Master 上云成功（NEF 计时留证）
- [ ] 询问弹层逐项确认删除后，图库/盘点一致（pruneAbsent 无孤儿）
