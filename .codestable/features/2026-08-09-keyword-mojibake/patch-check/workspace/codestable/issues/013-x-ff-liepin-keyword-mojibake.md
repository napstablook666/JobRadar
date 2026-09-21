---
kind: issue
title: "APP 搜索关键词乱码修复"
type: ff
status: closed
created: 2026-08-09
epic: ""
---

# APP 搜索关键词乱码修复

## 做了什么

修复历史重复转码关键词在 APP 配置页、断点列表和搜索运行时的乱码问题；保存时统一写入规范 JSON。

## 改了哪些

- `front/lib/keyword-lines.ts`、`front/app/liepin/page.tsx` — 读取关键词和断点时还原旧编码文本。
- `KeywordParser`、`LiepinPageProgress`、`LiepinService` — 统一解析、断点匹配和保存规范化。
- 相关 Java 测试、`进度白板.md` — 补充回归与本轮记录。

## 怎么验证的

- Java 定向/全量测试通过；前端 `pnpm lint`、`pnpm build` 和 `git diff --check` 通过。
- 真实页面回读旧数据库值后，文本框显示 18 个正常中文关键词；截图保存于 `target/codex-git/keyword-mojibake-ui.png`。

## 对 codestable/ 的影响

- 新增本条快改记录；没有长期规格真相需要同步。
- 现有数据库保留原值，运行时读取修复，下一次保存完成规范化。
