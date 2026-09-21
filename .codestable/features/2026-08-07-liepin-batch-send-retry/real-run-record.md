# 猎聘批量发送真实运行记录

## 运行条件

- 后端重载后状态接口包含 `aiTimeouts`、`invalidResults`、`buttonFailures`。
- 登录状态：`isLoggedIn=true`。
- 模式：`BATCH_AUTO`。
- 为保证本轮只验证一条，启动前临时将 `maxPerRun` 从 `10` 调为 `1`。

## 运行结果

- 启动接口：`POST /api/liepin/start`，返回 `success=true`、`runId=1`。
- 处理岗位：`42`。
- AI候选：`30`；评分通过：`1`；评分请求：`6`；话术请求：`1`。
- AI超时：`0`；结果无效：`0`；按钮失效：`0`；确认失败：`0`。
- 聊天确认成功：`1`；自动发简历确认：`1`；最终状态：`COMPLETED`。
- 最终状态消息：`投递任务完成，共发起1个聊天，处理42个岗位，薪资跳过9个，其它筛选跳过0个`。

## 收尾

- 成功确认后没有继续发送。
- `maxPerRun` 已恢复为 `10`。
- 最终状态：`isRunning=false`、`delivered=1`、`lastDeliveredCount=1`。
- 运行日志：`target/logs/get-jobs.log`；后端启动日志：`target/local-run/backend-bootRun.log`。
