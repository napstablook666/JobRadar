# 执行记录

## 目标

按已确认的 `isolated`、线程池大小 `4`、默认 `background` 方案完成四个平台并行投递，并修复手动构造服务对象时异步入口退化为同步执行的问题。

## 已完成

- 四个平台接入独立 Playwright runtime、独立访问锁、Cookie 状态和生命周期收尾。
- 投递线程池统一使用 `deliveryExecutor`，默认大小由 `GETJOBS_DELIVERY_EXECUTOR_SIZE` 控制，默认值为 `4`。
- 四个平台控制器改为统一异步启动入口；任务先占位再进入线程池。
- 默认后台运行使用无界面参数；登录扫码入口检查并要求 `visible-login`。
- 四个平台服务的字段初始化回退从 `Runnable::run` 改为 `ForkJoinPool.commonPool()`。

## 偏差与边界

- Spring 正常运行时仍由 `deliveryExecutor` Bean 注入，ForkJoin 回退只服务手动构造、测试和缺少注入的对象。
- shared 模式保留作兼容回退，不与默认 isolated 路径混用。
- 受 Playwright Java 对象线程安全约束，平台内部仍串行访问自己的 Page；并行边界在平台之间。
