# 城市编码归一化验证记录

## 基线

- 三个已有源码文件的基线哈希与实现后哈希记录在 `baseline-hashes.txt`。
- 当前运行实例重载前状态：`taskState=FAILED`，错误值为 UTF-8 乱码，`isRunning=false`。
- 重载前配置：城市 `全国`，`maxPerRun=10`。

## 源码与测试

- `.\gradlew.bat test --tests com.getjobs.application.service.LiepinCityNormalizationTest --tests com.getjobs.application.service.ConfigServiceCityConfigTest --tests com.getjobs.application.controller.LiepinControllerCityConfigTest --no-daemon`
  - `BUILD SUCCESSFUL`
- `.\gradlew.bat test --no-daemon`
  - `BUILD SUCCESSFUL`
- `pnpm lint`
  - exit code `0`
- `pnpm build`
  - exit code `0`
- `git diff --check`
  - exit code `0`；仅报告工作区既有换行符提示
- `git apply --reverse --check --whitespace=nowarn city-code-normalization.patch`
  - `reverse check passed`

## 运行态

- `restart.bat`
  - JDK 21、前端和后端均启动成功
  - 后端端口就绪，状态回读为 `IDLE`
- 使用新后端实例 PUT 城市码 `410`
  - 保存响应城市：`全国`
  - 再读配置城市：`全国`
- 使用新后端实例 PUT 未知值 `BAD_CITY`
  - HTTP 状态：`400`
  - 响应：`{"success":false,"message":"未在数据库中找到城市编码: BAD_CITY"}`
- 原配置恢复校验：城市 `全国`，`maxPerRun=10`，任务 `IDLE`
- 真实投递小批启动响应：`success=false`、`status=not_logged_in`；登录态门槛阻止了浏览器投递，未产生岗位发送副作用。

## 结论

城市名、城市码和历史 UTF-8 乱码已共用同一解析链路；规范值保存、未知值 HTTP 400 和运行实例加载均已验证。
