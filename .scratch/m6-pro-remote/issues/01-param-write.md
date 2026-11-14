# 01 · 参数可写（光圈/快门/ISO/曝光补偿）

> Status: ready-for-agent
> 上游：`.scratch/m6-pro-remote/spec.md`

## 范围

- 读当前值（已有 0x1015 兼容读法）+ 读允许范围/枚举（优先尼康 `GetDevicePropDescEx`，失败则按已知档位表）
- 写：`SetDevicePropValueEx`（尼康）优先，回退 `SetDevicePropValue(0x1017)`
- 属性：光圈 `0x5007`、快门 `0x500D`、ISO `0x500F`、曝光补偿 `0x5010`、自动 ISO 开关
- 写入后回读校验；相机夹取/拒绝时 UI 明示
- 镜头收起（焦距 0）时提示「请拧出镜头」

## 验收

- [ ] 单测：写入请求字节、夹取逻辑、拒绝/回读不一致的处理
- [ ] 真机：光圈 f/4→f/5.6、快门 1/10→1/60、ISO 400→800 各写成功且回读一致
- [ ] 真机：相机拒绝的值（如 f/2.8 超出镜头范围）给出人话提示
- [ ] 日志出现 `SetDevicePropValueEx` 的请求/响应码

## 协议待确认

- 尼康 `SetDevicePropValueEx` 操作码与载荷（属性码 u32 + 值 + 可选校验），真机 probe 后定
