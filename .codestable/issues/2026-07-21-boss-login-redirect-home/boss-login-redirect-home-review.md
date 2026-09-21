---
doc_type: issue-review
issue: 2026-07-21-boss-login-redirect-home
status: approved
reviewer: self
reviewed: 2026-07-22
round: 2
lane_a_state: unavailable
lane_a_ref: ""
lane_a_reason: "IndependentAgentUnavailable → prior ApproveLocalOnly continues"
lane_b_state: completed
lane_b_ref: ""
lane_b_reason: "local line review of hands-off login path"
---

# boss-login-redirect-home 代码审查报告（round-2）

## 1. Scope And Inputs

- Fix-note: `boss-login-redirect-home-fix-note.md`（round-3 hands-off，confirmed）
- Evidence: `target/local-run/boss-login-stay-hands-off.json`（20s PASS）
- Log: `target/local-run/bootRun-hands-off.log`（detach + json/new + 稳定停留 16s+）
- Diff basis: `PlaywrightManager.java`、`BossController.java`
- Review mode: re-review after material login-path change
- Reviewer: self（local-only 延续）

## 2. Diff Summary

- `PlaywrightManager.java`：系统 Chrome CDP；hands-off detach/`/json/new`/poll/reconnect；删除 PW 登录导航
- `BossController.java`：logout 先清 Cookie 再 setLoginStatus(false)
- 反检测脚本：保留前轮，非本轮主修复

## 3. Findings

### blocking

- [x] REV-001 登录页可停留 — **closed**
  - Evidence: 产品 hands-off 路径 `/web/user` 连续 20s PASS；日志 stableTicks≈16s+
  - 对比：PW 附着路径仍会回跳（历史 evidence round4）

### important（residual，不阻塞本 issue）

- [ ] REV-010 hands-off 期间共享浏览器四平台自动化暂停（设计取舍）
- [ ] REV-011 `browser.close()` 偶发 `Cannot find command to respond: 17`（目前 CDP 仍可达）
- [ ] REV-012 非 CDP/捆绑 Chromium 模式无法 hands-off 登录引导

### nit

- [ ] REV-013 重连后可能累计多余标签页（findOrCreate 有域匹配，仍可能新建）
- [ ] REV-002/003 前轮 Client Hints / chromeMajor：CDP 真 Chrome 路径下优先级下降

## 4. Adversarial Pass

- 攻击点：把“指纹绿”当“登录绿” → 本轮以 **list-only 停留** 验收，不靠 stealth 自证
- 攻击点：init 并发中 detach 打爆其它平台 → `initializationComplete` 门闸已加
- 攻击点：logout 已是 false 不重开登录 → setLoginStatus 引导移出 statusChanged 门闸
- 攻击点：超时重连后死循环 hands-off → 重连用 `loginStatus.put` 不调 setLoginStatus(false)

## 5. Verdict

- Status: **approved**
- REV-001 closed with product-path stay evidence
- 可进入 ConfirmFixCompletion / QA 收口
- 建议用户：在已打开的 Boss 登录页完成扫码；应用会在离开 `/web/user` 后重连并落 Cookie

## 6. Residual Risk

- 完整扫码成功依赖人工一次操作（自动化无法在 hands-off 阶段点控件）
- 共享浏览器 pause 窗口内其它平台任务应避免启动
