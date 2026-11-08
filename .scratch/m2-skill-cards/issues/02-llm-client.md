# 02: LlmClient 缝隙 + OkHttp 管线

**What to build:** `LlmClient` 接口（`complete(prompt, imageJpeg: ByteArray): Result`）；
`FakeLlmClient`（脚本返回/超时/429/400 全路径，JVM）；
`OkHttpLlmClient`（手搓 chat.completions 请求体：messages 拼装 + inline base64 image_url + temperature 参数占位）；
响应解析（text 提取 + usage token 数）；Ledger 行写入（smart ledger 表）。

**Blocked by:** 01

**Status:** resolved

## Comments

- `LlmClient` 缝隙：`complete(cfg, prompt, imageJpeg?)` → `LlmOutcome`（Text(token 透传) / RateLimited(Retry-After) / Http(code+片段) / NetworkTimeout(reason)）——**绿色四通道**，Pass 执行器（04）无逻辑分支歧义。
- `OkHttpLlmClient` 薄实现：手撸 chat.completions（messages[0].content 数组：text + image_url base64 NO_WRAP）；Authorization Bearer；read 90s；429 提取 Retry-After；usage → prompt/completion tokens。
- **Ledger 表 v4**：`ledger`（objectName/model/promptChars/withImage/outcome/tokens/时间）+ DAO（insert/recent 200/okCount）——语义=GLOSSARY Ledger（不存 prompt 全文）。
- `FakeLlmClient`：队列吐出式（缺省 Text("fake")）+ calls 观测；6 例 JVM 测试（顺序吐出/token 速率/原因路径/cfg 传递）。
- 证据：**56/56 全绿**（+FakeLlmClientTest 6）；`assembleDebug` 通过；装机 Success。

- [ ] LlmClient 接口 + 结果密封类型（Text/RateLimited/Http(x)/NetworkTimeout）
- [ ] FakeLlmClient 4 路径测试
- [ ] OkHttpLlmClient（请求体拼装 bug-防：json 转义、content 多元素数组）
- [ ] Ledger 写入 + 明细字段（模型/promptLen/tokens/成败）
