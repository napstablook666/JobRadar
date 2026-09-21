---
kind: issue
title: "猎聘、51job、智联整页 JD 兜底筛选"
type: feature
status: open
created: 2026-08-16
epic: ""
---

# 猎聘、51job、智联整页 JD 兜底筛选

## 目标

当岗位接口字段和详情页精确定位都未取得可用 JD 时，猎聘、51job、智联能从该岗位详情页复制净化后的可见正文，并将其用于 AI 分析、筛选和后续话术生成。

## 范围

- 包含：猎聘、51job、智联的 JD 提取回退、网页噪声清理、AI 输入限长和相关回归测试。
- 不包含：Boss 直聘、真实数据库文件改写、投递确认规则调整。

## 背景与证据

- 51job 现有缓存、DOM、内嵌 JSON 和标题区段回退，仍会在页面结构变化时得到 `jd_missing`。
- 猎聘已有详情页 JD 读取，但暂缓岗位重试只依赖旧快照，快照为空时不会再次尝试详情页。
- 智联当前既不保存 JD，也没有 AI JD 筛选入口，无法覆盖详情页文字缺失的岗位。

## 现状如何工作

平台从搜索响应或详情页字段读取 JD；AI 模式将 JD 和候选人资料送入既有结构化评分协议，未通过的岗位不会继续投递。

## 影响范围

- 必须修改：岗位正文提取、猎聘/51job JD 回退、智联岗位数据与 AI 筛选链路。
- 需要验证：网页噪声截断、访问验证拒绝、AI 筛选门禁、旧表字段兼容和默认投递行为。
- 仍待调查：各招聘站点生产页面选择器的实际变化频率。

## 质量目标

- 功能适宜性：JD 缺失时，三个目标平台都能在详情页存在可读岗位正文时取得可供 AI 评分的文本。
  - 来源：用户请求。
  - 预期证据：正文清洗单元测试和各平台解析调用链测试/编译。
- 可靠性：智联 AI 筛选默认开启；明确关闭后保持既有投递行为。正文读取、AI 请求或访问验证失败，以及 REVIEW/普通 SKIP 结果，不以不明结论继续投递或永久过滤；明确硬不匹配或低分 PASS 才写入已过滤。
  - 来源：现有投递门禁与本次风险扫描。
  - 预期证据：配置默认值测试、定向测试和全量测试。
- 信息安全性：招聘页面的文本仅作为待分析资料，不能改变结构化评分协议或输出格式。
  - 来源：网页正文作为外部不可信输入。
  - 预期证据：默认评分提示词约束与提示词回归测试。

## 方案判断

新增共享正文提取器而不让各平台各自截取 `body.innerText`：调用方先尝试平台接口和精确选择器；只有这些来源无效时，复制详情页可见正文，移除页面控件、导航、弹层和推荐区，并按岗位章节和长度上限收敛后再进入 AI。

## 执行记录

- 已建立本任务 Git 检查点：`target/codex-git/checkpoints/20260816-083313-whole-page-jd-fallback`。
- 已新增共享正文提取器，并接入 51job 的最后一级详情页回退、猎聘的精确选择器/整页回退和暂缓重试补读。
- 智联默认开启岗位级 AI JD 筛选，并保留配置开关；`enable_ai_screening IS NULL` 才迁为开启，历史 `0` 按关闭保留，用户之后明确关闭也会保留。`ai_screening_configured` 仅作一次性标记，不再用它覆盖 enable。
- AI 评分提示词在最终渲染阶段无条件追加网页资料不可信边界，覆盖默认和自定义筛选模板。
- 正文提取拒绝访问验证、安全验证、人机验证、验证码和滑块验证页面，避免将验证墙作为 JD 送往 AI。

## 验证

- `JAVA_HOME='/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot' ./gradlew test --tests com.getjobs.worker.utils.JobDescriptionExtractorTest --tests com.getjobs.worker.job51.Job51BehaviorTest --tests com.getjobs.worker.liepin.LiepinAiAssessmentTest --tests com.getjobs.worker.zhilian.ZhilianAiScreeningTest --tests com.getjobs.application.service.AiPromptTest --tests com.getjobs.application.service.ZhilianServiceAiScreeningMigrationTest`：通过，覆盖共享提取器、51job、猎聘、智联、AI 提示词和智联配置迁移夹具；历史 `enable_ai_screening=0` 保留关闭，仅 `NULL` 迁为开启。
- `JAVA_HOME='/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot' ./gradlew test`：通过。
- `cd front && pnpm lint`：通过。
- `cd front && pnpm build`：通过。
- `git diff --check`：通过。

## 关闭回写

- project spec / epic spec：无需预期回写；本项是现有 JD 读取与筛选链路的实现增强。
