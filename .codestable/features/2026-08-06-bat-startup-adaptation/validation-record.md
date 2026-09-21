# BAT 启动脚本适配验证记录

## 范围

本次只涉及本地启动包装和 PowerShell 启停脚本；工作区其他业务修改保持原样。

## 基线 SHA-256

| 文件 | 基线哈希 |
|---|---|
| `bin/start-services.ps1` | `ECDF4622A88362E639DCADA37142E8A624E74E550656E5D74BB1A0E452081CBC` |
| `bin/stop-services.ps1` | `822FD3227E3160BCF71C5A0304EA3330C857A1B5C255F515D5C207B08443BA23` |
| `start.bat` | `FD453D35C7628B1EE2F2EBF4A2568B87553ACA2CDF8AE00337939E5B29A2A576` |
| `stop.bat` | `8B88516A5DB23888FAEDF15B5D5338BFBF98080F219736A44CE7039AF8F9E72B` |
| `一键启动.bat` | `FD453D35C7628B1EE2F2EBF4A2568B87553ACA2CDF8AE00337939E5B29A2A576` |
| `一键停止.bat` | `8B88516A5DB23888FAEDF15B5D5338BFBF98080F219736A44CE7039AF8F9E72B` |
| `bin/kill-services.bat` | `41E76FE534A281889B842B83D17DB7D6D87281B9A2F98AC163C2C5AF6E859A43` |
| `进度白板.md` | `EBB6C33C4E1EF288E39CD5C868B262F93F18BF76A98BB9C55090E50298A2B591` |

## 修改后 SHA-256

| 文件 | 修改后哈希 |
|---|---|
| `bin/start-services.ps1` | `517CBCB09F0C7AD9AA53E5833F6B80B888B62C6D67068D8DF7CD0418F93A332D` |
| `bin/stop-services.ps1` | `2396759F80E69B4665729E70381788378561DDAF206153D3639949545CB90939` |
| `start.bat` / `一键启动.bat` | `DC4CF20D294F5588D98E41EC98F96E5210CDD6C8B00EF349E9E609AA94919B8F` |
| `stop.bat` / `一键停止.bat` | `6E7DFF246BF8418BD6666403A52B54A9709C615A82245BD8E92D6FC2F228D929` |
| `bin/kill-services.bat` | `101B21DAF5098836FC5B072858F598B357745B4E3A3858DD261C1D41EF21CE6E` |
| `进度白板.md` | `0B487FE0B043AAEA1122D130F03456210CBA459B69612AB0325B11CA4DF39917` |

## 命令与字面结果

| 命令 | 字面结果 | 退出码 |
|---|---|---:|
| PowerShell 解析 `bin/start-services.ps1`、`bin/stop-services.ps1` | `PS_PARSE=0` | 0 |
| 从 `C:\Windows` 执行 `call "D:\Codeg 部署\get_jobs\start.bat" -NoBrowser` | 前端 ready `~9s`；后端 port ready `~12s`；`START_EXIT=0` | 0 |
| 访问 `http://127.0.0.1:6866/` | `FRONT=HTTP_200`；启动时端口 `True,True` | 0 |
| 从 `C:\Windows` 执行 `call "D:\Codeg 部署\get_jobs\一键停止.bat" -Quiet` | `STOP_EXIT=0 PORTS_AFTER=False,False` | 0 |
| `git diff --check -- bin/kill-services.bat` | `GIT_DIFF_CHECK_TARGET=0` | 0 |
| 读取生成的 `target/local-run/run-front.bat`、`run-backend.bat` | `call pnpm.cmd dev`、`call gradlew.bat bootRun` | 0 |
| 在 `rollback-check-2` 副本执行回滚脚本 | `ROLLBACK_OK`；`ROLLBACK_SCRIPT_EXIT=0 MARKERS_AFTER=False,False,False,False` | 0 |

## 已验证行为

1. 中文和空格路径下，包装脚本仍能定位项目根目录。
2. `-NoBrowser` 参数透传成功，启动命令退出码为 `0`。
3. 后端使用 TCP 监听状态判断就绪，不再依赖根路径 HTTP 状态码。
4. 停止脚本释放 `6866`、`8888` 端口并返回 `0`。
