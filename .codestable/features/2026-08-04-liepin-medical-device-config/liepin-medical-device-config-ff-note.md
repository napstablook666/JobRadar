---
doc_type: feature-ff-note
feature: liepin-medical-device-config
date: 2026-08-04
requirement:
tags: [liepin, config, medical-device]
---

## 做了什么
按用户确认的条件更新猎聘岗位筛选：18 个放疗/医疗设备相关关键词、城市“全国”、薪资 6k-10k。

## 改了哪些
- 通过现有 `PUT /api/liepin/config` 更新 `liepin_config` 配置记录。
- 关键词保存为 JSON 数组，薪资码保存为 `6$10`；未修改源代码、登录 Cookie 或投递状态。
- 更新项目根目录 `进度白板.md`。

## 怎么验证
- `GET /api/liepin/config` 回读到 18 个关键词、城市“全国”、薪资码 `6$10`。
- SQLite 回读确认 `liepin_config` 的关键词数量为 18，城市为“全国”，薪资码为 `6$10`。
- 猎聘 Cookie 的 `updated_at` 保持 `2026-08-04T21:27:07.687082300`，`GET /api/liepin/status` 确认任务未运行且账号已登录。

## 对 `.codestable/` 的影响
- 仅新增本次快改记录，不改变现有规格或历史事项。
