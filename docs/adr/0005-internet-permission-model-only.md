# ADR-0005: M2 引入 INTERNET 权限——模型 API 域白名单姿态

- **状态**: Accepted（M2 grilling 2026-11-08，Q5 用户拍板）
- **日期**: 2026-11-08

## Context（背景）

M0/M1 的隐私底线是"无 INTERNET 权限"（ADR 与 ManifestPrivacyTest 钉死）。M2 引入云端大模型后，App 必须联网调 OpenAI 兼容 API。

## Decision（决策）

1. AndroidManifest 增加 `<uses-permission android:name="android.permission.INTERNET"/>`
2. **隐私姿态升级而非倒退**：联网被收窄为"仅模型 API 域"——`ManifestPrivacyTest` 反转为断言：
   - 存在 INTERNET 权限（M2 起）
   - **不存在任何其他危险权限**（定位/存储/相机等依旧零声明；用户图中素材全走应用私有目录 + SAF，无系统相册读权限需求）
3. 设置页新增**云处理总开关**（关=所有 LLM 请求挂起）+ **明细日志**（Master 引用/模型/prompt 长度/token/成败，Room）

## Consequences（后果）

- 正面：隐私承诺可验证可审计（测试白名单 + Ledger 全记录），不豁免任何系统敏感权限
- 负面：用户初次安装的权限弹窗叙事从"完全离线"改为"仅在你开启云端处理时联网"（产品文案需更新）
- 测试：`ManifestPrivacyTest` 更名为断言 V2；无自有服务器，流量仅指向用户配置的模型 base_url

## 参考

- M2 grilling Q5；`GLOSSARY.md`: 明细日志（Ledger）
- 原 M0 隐私叙事：`docs/SPEC-Lumina.md` §5
