# 01 · BLE 配对与唤醒（SnapBridge 方言）

> Status: claimed（代码完成，单测绿；真机验收见 04）
> 上游：`.scratch/m5-remote/spec.md`、`docs/adr/0006`

## 范围

- `com.lumina.ble`：GATT 扫描（尼康 LSS 服务/`0x0399` 厂商数据）、连接、四步配对握手（17B 帧）、读 `0x2004` 凭据并解密、写 `0x2005=0x01` 唤起 Wi-Fi。
- Blowfish（标准算法 + 尼康定键）、CBC-MAC 式 hash、LSS 会话密钥派生、97B/98B 凭据布局。
- 配对身份（deviceId/nonce/serial）与 SSID/密码持久化（私有 prefs）。

## 验收

- [ ] `BlowfishTest`：已知向量 + 尼康 hash 自洽
- [ ] `PairingTest`：stage1 编码 17B、stage2→stage3 用固定 salt 命中、stage4 序列号提取
- [ ] `WifiCredentialsTest`：97B 布局 + 定键解密回环可解出 SSID/密码；非可打印字节拒绝
- [ ] 真机：列表出现 Z30 → 连接 → 状态到"已配对" → 读到 SSID（`m5-remote.log` 有分步记录，无密码）
- [ ] 权限被拒/蓝牙关闭时有人话提示，不崩

## 证据

- 单测：`gradle -p . :app:testDebugUnitTest --no-daemon` → BUILD SUCCESSFUL
  - `BlowfishTest` 4（标准向量）、`PairingTest` 6（17B 帧/salt 命中/序列号）、`WifiCredentialsTest` 6（97B 回环/定键/LSS 候选）
- APK：`build-out/outputs/apk/debug/app-debug.apk`（约 10.1 MB，2026-11-14）
- **真机（Z30 + Xiaomi 24122RKC7C）已过 BLE 段**：扫描到 `Z_30_8182949` → 四步握手 → `0x2004` 解密命中（`lss-kdf ctx=na1-3,x0-3 k1=stage4 lay=l98`）→ 唤醒 Wi-Fi。
- **真机修正 1**：解密出的 SSID 是 `NIKON_Z_30_82949_SnapBridge`，相机实际广播 `NIKON_Z_30_82949`；已剥后缀（扫描实测）。
- **真机修正 2**：配对后约 50s 无 BLE 流量相机收热点/断链；已保持 GATT + 20s TIME 心跳 + NOT1 连接位应答 + 入网前重发 0x2005。
- **未闭环**：`0x2005` 写入被相机接受，但相机热点仍未自行广播；当前用手动 Wi-Fi 入网。疑似需要经典蓝牙 bonding / 相机侧确认，留作后续。
