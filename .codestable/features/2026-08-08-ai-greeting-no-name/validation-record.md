# AI 招呼语固定开头与姓名过滤验证记录

日期：2026-08-08

## 基线与目标

- 基线配置：`ai-config-baseline.json`，快照哈希 `CC73692B492B59B924576AB3ECAC80F5F55CD8D4AF3052CC35DD5AF3D4E283D8`。
- 目标配置：`ai-config-modified.json`，快照哈希 `95DC424C8F534F684D793606BA44BA730CD8B9B36787925771BBEC8DCA04C46E`。
- 目标字段：`prompt`、`messagePrompt` 固定前缀；话术正文过滤候选人姓名、命名表达、Markdown、多行和超长输出。

## 代码与补丁

- Java：`.\gradlew.bat test --no-daemon` → `BUILD SUCCESSFUL in 32s`，退出码 `0`。
- AI 配置页：`pnpm exec eslint app/ai-config/page.tsx` → 无输出，退出码 `0`。
- 前端构建：`pnpm build` → `Compiled successfully`、静态页面 `14/14`，退出码 `0`。
- 差异检查：`git diff --check` → 无空白错误，退出码 `0`；仅保留既有 CRLF 转换提示。
- 补丁：`ai-greeting-no-name.patch`，SHA256 `5E95BAF5101126CA740841A3B5F50DC42BD5313C8E2A7C95DECF011D38467512`。
- 补丁预检和应用：`git apply --check --ignore-whitespace --directory=patch-check-2`、`git apply --ignore-whitespace --directory=patch-check-2` 均为退出码 `0`；6 个隔离文件规范化文本哈希全部匹配目标副本。

## 回滚

- 源码回滚：`rollback-source-ai-greeting.ps1 -Root patch-check-2` 输出 `SOURCE_ROLLBACK_OK`，退出码 `0`；6 个隔离源码/测试文件与基线规范化文本全部匹配。
- 运行态回滚：`rollback-ai-greeting.ps1` 输出 `AI_CONFIG_ROLLBACK_OK`，四个配置字段回读匹配，退出码 `0`。
- 回滚验证后已重新写入目标配置，四个字段回读 `targetMatches=true`。

## 真实预览

- `POST http://localhost:8888/api/ai/preview`：HTTP `200`，`success=true`。
- 字面结果：`您好，我是应用物理学应届毕业生，具备放疗设备现场流程实习经验，期待沟通`。
- 校验：固定前缀 `true`、单行 `true`、长度 `35`、姓名过滤 `true`。
- 服务状态：前端 `http://localhost:6866/ai-config` HTTP `200`；后端配置接口 HTTP `200`；监听端口数量 `2`。
