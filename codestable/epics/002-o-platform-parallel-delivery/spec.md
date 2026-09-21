---
kind: epic
title: "多平台并行投递与后台浏览器运行时"
status: active
created: 2026-08-08
---

# 多平台并行投递与后台浏览器运行时

## 这个 Epic 要改变什么

四个平台的投递任务可以同时进入各自的 Playwright 运行时，互不争用同一个页面；默认浏览器在后台运行，登录扫码时再切换到可见窗口。

## 已确认方案

- Playwright 运行时默认使用 `isolated`：每个平台拥有独立的 Playwright、BrowserContext、Page、Cookie 状态和访问锁。
- `shared` 仍作为兼容回退值，保留旧共享浏览器链路。
- 投递专用线程池默认大小为 `4`，每个平台任务先原子占位，再异步提交。
- 浏览器默认 `background`，使用无界面启动参数；扫码登录要求 `visible-login`。
- 配置入口：`GETJOBS_DELIVERY_RUNTIME`、`GETJOBS_DELIVERY_EXECUTOR_SIZE`、`GETJOBS_BROWSER_MODE`。

## 当前实现

- `PlatformBrowserRuntime` 收拢单个平台的启动、Cookie、登录状态、页面恢复、访问锁和关闭生命周期。
- `PlaywrightManager` 根据运行时配置创建四个平台运行时，并保留 shared 模式的兼容分支。
- 四个平台服务使用同一个投递线程池容量，但各自调用平台运行时锁；启动接口统一走 `startDeliveryAsync`。
- 手动构造服务对象时，异步执行器回退到 `ForkJoinPool.commonPool()`，避免测试替身把异步任务退化成当前线程同步执行。
- 停止、登录和关闭流程分别保留取消检查、可见登录边界和运行时资源释放。

## 质量约束

- 功能适宜性：四个平台的启动接口立即返回，并且每个平台最多保留一轮运行中的任务。
- 性能效率：独立运行时允许不同平台并行；默认后台模式不创建可见窗口，投递线程池容量为 4。
- 可靠性：等待浏览器访问权时可取消；任务完成、失败、停止和关闭都会释放平台运行时资源。
- 可维护性：平台资源边界集中在 `PlatformBrowserRuntime`，shared 模式保持可回退，配置值有明确默认值。

## 暂不推进范围

- 不改变平台投递业务规则、筛选条件、数据库结构和前端交互文案。
- 不自动将 Epic 关闭或移动到 `done/`。

## 关闭条件

- 四个平台任务可独立提交，运行时状态接口能区分平台状态。
- Java 全量测试通过；后台/可见登录模式和可取消等待有自动化验证。
- patch、验证记录和反向回滚脚本均可复现。

## 相关材料

- `src/main/java/com/getjobs/worker/manager/PlatformBrowserRuntime.java`
- `src/main/java/com/getjobs/worker/manager/PlaywrightManager.java`
- `src/main/java/com/getjobs/application/config/AsyncConfig.java`
- `execution.md`
- `verification.md`
- `delivery-runtime-fallback.patch`
- `rollback.ps1`
