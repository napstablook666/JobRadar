# 侧边栏可折叠验证记录

## 基线

- 已保存 `front/app/layout.tsx`、`front/app/components/Sidebar.tsx`、`front/app/components/ContentArea.tsx` 和 `进度白板.md` 的基线副本。
- 哈希明细见 `baseline-hashes.txt`。

## 静态检查

命令：`npx eslint app/layout.tsx app/components/Sidebar.tsx app/components/ContentArea.tsx`

字面输出：

```text
D:\Codeg 部署\get_jobs\front\app\components\Sidebar.tsx
  70:6  warning  React Hook useEffect has a missing dependency: 'checking'. Either include it or remove the dependency array  react-hooks/exhaustive-deps

✖ 1 problem (0 errors, 1 warning)
```

退出码：`0`

命令：`npm run build`

字面输出摘要：

```text
✓ Compiled successfully
Running TypeScript ...
✓ Generating static pages (13/13)
```

退出码：`0`

补充：全量 `npm run lint` 仍被仓库其他文件现有的 36 个 error 阻断，本次目标文件没有新增 error。

## 浏览器验证

地址：`http://127.0.0.1:6866/env-config`

- 初始状态：折叠按钮为“折叠侧边栏”，`aria-expanded=true`，侧边栏宽度 `256`，内容区左边距 `256`。
- 点击折叠：按钮变为“展开侧边栏”，`aria-expanded=false`，本地存储 `get-jobs-sidebar-collapsed=true`，侧边栏宽度 `80`，内容区左边距 `80`。
- 路由切换：进入 `/liepin` 后仍保持图标栏，导航项和页面内容正常显示。
- 刷新页面：仍读取到 `get-jobs-sidebar-collapsed=true`，宽度保持 `80`，内容区左边距保持 `80`。
- 已保存展开态和收起态截图：`sidebar-expanded.png`、`sidebar-collapsed.png`。

## 回滚脚本校验

命令：`[System.Management.Automation.Language.Parser]::ParseFile(...)`

字面输出：`parse_errors=0`

退出码：`0`

隔离回滚命令：`rollback-sidebar-collapse.ps1`

字面输出：

```text
Rollback complete: sidebar collapse files restored from baseline.
rollback_hash_matches=1
```

退出码：`0`
