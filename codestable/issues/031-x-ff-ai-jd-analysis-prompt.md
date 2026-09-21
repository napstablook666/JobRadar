---
kind: issue
title: "AI JD 分析提示词与测试区域"
type: ff
status: closed
created: 2026-08-15
epic: ""
---

# AI JD 分析提示词与测试区域

## 做了什么

- AI 配置页新增独立的 AI JD 分析提示词和测试区域，内置放疗应用方向的优先保留与直接淘汰规则。
- Boss 与猎聘的 AI 筛选统一组合业务规则和原批量评分 JSON 协议；猎聘单岗位回退评分同步使用新规则。
- 新增 JD 分析预览接口，可在不保存、不投递的情况下测试岗位。

## 改了哪些

- 前端补充提示词编辑、测试 JD、阈值输入、加载/错误/结果与复制状态。
- 后端增加 `jd_analysis_prompt` 配置字段、旧 SQLite 表升级、提示词组合与严格预览结果校验。
- 增加默认规则和提示词组合回归测试。

## 怎么验证的

- `mvn -q test`：通过。
- `cd front && pnpm exec tsc --noEmit && pnpm lint`：通过。
- `cd front && pnpm build`：通过；`/ai-config` 静态生成成功。
- `git diff --check`：通过。
- 浏览器自动化受本机 agent-browser 策略锁阻塞；用运行中的 `http://127.0.0.1:6866/ai-config` HTML 检查确认包含“AI JD 分析”。
- 本地提交：`122d313`。

## 对 codestable 的影响

无影响；新增的是可编辑筛选规则与操作区域，未改变现有项目规格边界。

## 51job 后续适配（2026-08-15）

- 51job AI 模式改为先执行 `jdAnalysisPrompt + screenPrompt` 结构化分析，仅 `PASS`、达到配置阈值且无 `HARD_MISMATCH` 的岗位继续生成招呼语或投递。
- 缓存 JD、详情页 JD 与详情加载失败后的搜索页投递均受同一筛选门禁控制；分析无效、`REVIEW`、`SKIP`、低分和硬冲突统一跳过。
- 51job 配置新增 `ai_min_score`，默认 70，支持 0-100；前端可编辑并完整保留阈值 0。
- 验证：JDK 21 Java 全量测试、51job/AiPrompt 定向测试、前端 ESLint、TypeScript、Next.js 生产构建和 `git diff --check` 均通过。
- 独立只读审查结论为“无阻塞问题”；残余风险是尚未进行真实 51job 小批量投递复测。
- 检查点：`target/codex-git/checkpoints/20260815-115453-51job-jd-analysis`。
