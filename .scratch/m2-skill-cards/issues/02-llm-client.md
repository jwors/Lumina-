# 02: LlmClient 缝隙 + OkHttp 管线

**What to build:** `LlmClient` 接口（`complete(prompt, imageJpeg: ByteArray): Result`）；
`FakeLlmClient`（脚本返回/超时/429/400 全路径，JVM）；
`OkHttpLlmClient`（手搓 chat.completions 请求体：messages 拼装 + inline base64 image_url + temperature 参数占位）；
响应解析（text 提取 + usage token 数）；Ledger 行写入（smart ledger 表）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] LlmClient 接口 + 结果密封类型（Text/RateLimited/Http(x)/NetworkTimeout）
- [ ] FakeLlmClient 4 路径测试
- [ ] OkHttpLlmClient（请求体拼装 bug-防：json 转义、content 多元素数组）
- [ ] Ledger 写入 + 明细字段（模型/promptLen/tokens/成败）
