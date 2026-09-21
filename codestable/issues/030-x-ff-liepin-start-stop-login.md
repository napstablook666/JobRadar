---
kind: issue
title: "修复猎聘启动登录恢复与停止收尾"
type: ff
status: closed
created: 2026-08-12
epic: ""
---

# 修复猎聘启动登录恢复与停止收尾

## 根因

猎聘独立浏览器运行时在懒加载模式下首次状态查询可能没有恢复已保存 Cookie；投递线程在 Playwright 导航、等待响应或 HTTP 回退期间被停止时，旧链路仍可能继续占用页面和访问锁，导致停止接口长期等待，状态也无法及时回到终态。

## 做了什么

- 为平台运行时增加活动操作代际、停止感知和无锁强制关闭；关闭页面、上下文和 Playwright 资源后，正在进行的操作会显式失败并释放任务资源。
- 猎聘停止接口同步等待最多 5 秒；超时后强制关闭运行时，清理普通投递和暂缓重试的运行状态、租约及 worker 计数。
- 停止标志贯穿猎聘搜索导航、`waitForResponse` 和 HTTP 回退分支，取消后不再发起新的浏览器或 HTTP 请求。
- 懒加载首次检查猎聘登录态时恢复并验证数据库 Cookie；停止后的状态轮询只读取缓存登录态，不重新创建浏览器。

## 改动文件

- `src/main/java/com/getjobs/worker/manager/PlatformBrowserRuntime.java`
- `src/main/java/com/getjobs/worker/manager/PlaywrightManager.java`
- `src/main/java/com/getjobs/worker/service/LiepinJobService.java`
- `src/main/java/com/getjobs/application/controller/LiepinController.java`
- `src/main/java/com/getjobs/worker/liepin/Liepin.java`
- 相关生命周期、停止收尾和搜索取消测试

## 怎么验证的

- JDK 21 下 `gradlew.bat test --no-daemon` 通过，`BUILD SUCCESSFUL`。
- JDK 21 下 `gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinSearchResponseTest` 通过。
- 直接调用现有本地依赖执行前端 ESLint 通过。
- 直接调用现有本地依赖执行 `next build` 通过，14 个静态页面构建完成。
- `git diff --check` 通过；pnpm 命令额外触发的依赖安装被项目当前 `ignored build scripts` 策略拦截，未产生源码错误。
- Codex Git checkpoint：`target/codex-git/checkpoints/20260812-145339-liepin-start-stop-login`
- 实现提交：`ff7bb5a`。

## 提交后核验

- 提交后 `codex-git.ps1 inspect`：工作区仅保留 checkpoint 前已有改动和未跟踪产物，暂存区为空。
- 提交范围未包含 `db/`、`target/`、`browser-data/`、日志、Cookie 或凭证。

## 对 codestable/ 的影响

- 新增本次快改记录；未修改 Cookie 格式、数据库结构、其他平台停止接口或投递参数。
