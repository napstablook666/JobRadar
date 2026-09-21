---
kind: issue
title: "修复模型列表下拉定位与滚动"
type: bug
status: closed
created: 2026-08-16
epic: ""
---

# 修复模型列表下拉定位与滚动

## 目标

环境配置页获取模型后，模型列表应在触发器附近完整展示，靠近视口底部时向上展开，模型数量较多时可以滚动选择，不再落在页面左下角或被截断。

## 修改

- `front/components/ui/select.tsx`：portal 下拉层改为视口固定定位；根据触发器上下可用空间选择展开方向，限制视口内宽度和最大高度；滚动页面或调整窗口后重新定位；打开时同步计算位置；补充 `aria-expanded`。
- `front/app/globals.css`：下拉层使用 flex 列布局，使列表区域的滚动约束实际生效。
- `front/app/boss/page.tsx`：Boss 多选下拉同步首次定位、上下展开和视口高度约束，避免全局下拉样式引入底部裁切。

## 验证

- `cd front && npm run lint`：通过。
- `cd front && npm run build`：通过，Next.js 16.0.1 静态构建和 TypeScript 检查通过；仅有 baseline-browser-mapping/Browserslist 数据过期提示。
- `git diff --check`：通过。
- 浏览器 CLI 已启动并访问静态构建页面；由于该页面的真实模型获取请求依赖后端且浏览器工具交互/网络拦截未能稳定触发模型列表，未将底部向上展开、长列表滚动末项、scroll/resize 跟随标记为人工验收通过。
- 临时静态服务、浏览器会话和临时浏览器配置已清理；用户 `%USERPROFILE%\\.agent-browser\\config.json` 已恢复。

## 交付记录

- Git 检查点：`target/codex-git/checkpoints/20260816-132225-fix-model-selector`
- 实现提交：`a026ead`（本地提交，未 push）。
- 验证记录提交：`1cfb819`（本地提交，未 push）。
- 本次 CodeStable 收尾提交：与本关闭记录一并提交（本地提交，未 push）。
