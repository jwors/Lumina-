# 06 · 云创 Picture Control 导入（spike）

> Status: ready-for-agent（先评估，不承诺）
> 上游：`.scratch/m6-pro-remote/spec.md`

## 范围

- 目标：把尼康云创配方（或本地 .NP3/.json）转换后写入 Z30 的优化校准槽位
- 先做 spike：确认配方格式、相机侧写入属性/数据块、老机身兼容降级规则
- 如果格式封闭/需要云端鉴权，明确放弃并记录

## 验收

- [ ] spike 结论（可行/不可行 + 证据）
- [ ] 可行则：导入一个配方，相机菜单能看到并生效；不可行则写 ADR 级结论
