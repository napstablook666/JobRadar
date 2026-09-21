# BAT 入口修复验证记录

日期：2026-08-09
范围：start.bat、stop.bat、restart.bat

## 基线与修改

基线文件保存在 baseline/，基线 SHA-256 记录在 baseline-hashes.txt。
修改后 SHA-256 记录在 modified-hashes.txt；当前工作区哈希再次计算结果一致：

    start.bat 68098AC51A3C3E14F08A26C5E9C22E42F1E2C0B3E307A53F6368597AA6A8D48D
    stop.bat 72969006ADEC1FB034A7F9CCB580F5F4D16CCCCAFE48A496713D098E959A706C
    restart.bat ED66B6434A6F2F501A1B62730E294280085E4D3328A036CE990AFF9FD2136E76

补丁文件：bat-entrypoint-repair.patch
回滚脚本：rollback-bat-entrypoint-repair.ps1

## 隔离回放

    PATCH_CHECK_EXIT=0
    PATCH_APPLY_EXIT=0
    ROLLBACK_CHECK_EXIT=0

隔离副本应用补丁后，start.bat、stop.bat、restart.bat 和进度白板的哈希均与 modified-hashes.txt 一致；执行回滚后四份文件均恢复 baseline/ 中的哈希。

## 运行回归

执行目录：D:\Codeg 部署\get_jobs

输入：

    set GETJOBS_NO_PAUSE=1
    call .\stop.bat -Quiet
    call .\start.bat -NoBrowser
    call .\restart.bat -NoBrowser

字面输出与退出码：

    [GetJobs] stopping from "D:\Codeg 部署\get_jobs"
    STOP_EXIT=0
    [GetJobs] starting from "D:\Codeg 部署\get_jobs"
    [OK] frontend ready (~9s)
    [OK] backend port ready (~8s)
    Admin page kept closed; open http://localhost:6866/ manually when needed.
    Start done
    Browser mode: background
    START_EXIT=0
    [GetJobs] restarting from "D:\Codeg 部署\get_jobs"
    [GetJobs] stopping from "D:\Codeg 部署\get_jobs"
    [OK] frontend ready (~9s)
    [OK] backend port ready (~9s)
    Restart completed successfully.
    RESTART_EXIT=0

## 服务与格式检查

    PORT_6866_OPEN=True
    PORT_8888_OPEN=True
    HTTP_6866=200
    http://127.0.0.1:8888/api/health STATUS=200
    BODY={"service":"GetJobs","status":"UP","timestamp":1786240947317}
    http://127.0.0.1:8888/api/playwright/status STATUS=200
    CRLF start.bat=0 lone LF
    CRLF stop.bat=0 lone LF
    CRLF restart.bat=0 lone LF
    DIFF_CHECK_EXIT=0

最终状态：前后端保持运行；浏览器管理页未自动打开。
