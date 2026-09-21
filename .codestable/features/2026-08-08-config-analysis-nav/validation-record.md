# 配置/分析导航去重验证记录

## 基线与修改范围

- 目标文件：4 个平台配置页。
- 基线快照：`baseline/`。
- 修改态哈希：`modified-hashes.txt`；基线哈希：`baseline-hashes.txt`。
- 补丁：`config-analysis-nav.patch`。

## 命令、字面输出与退出码

1. 基线哈希采集
   - 输入：`baseline/front/app/{51job,boss,liepin,zhilian}/page.tsx`
   - 命令：`Get-FileHash -Algorithm SHA256`，逐项写入 `baseline-hashes.txt`
   - 输出：`C355EED2A36827C0E55AE8320C9463AF853948B4B7A9048867ED2E500DEE413E`、`D0DAB212F7551AA36086A0519322108D8933DA23349AC048B8CA869DA0C4FE41`、`411EA7D73486E46A4CE2E7002373BD53338D58A912C625AAC64534CB61AB9CB0`、`595B1C2D9BF7C210CDA555CC1DE16DE2B0C3C3A65666536914B406975F0D2E11`
   - 退出码：`0`
2. 修改态哈希采集
   - 输入：`front/app/{51job,boss,liepin,zhilian}/page.tsx`
   - 命令：`Get-FileHash -Algorithm SHA256`，逐项写入 `modified-hashes.txt`
   - 输出：`7AB303B2E14F169DB7964C485E1BE80A63BDC61E24019C5EFA32CEA325D9A4F5`、`5B674DFAAACFE14E3C48572E6D05016CAF1604843E6DFC6BDE1D8A0C0A029DD5`、`B7EF756C2D8C9AF214E3A19954D9ADB3B7D10E815454F0FC34E734BD2259F847`、`683DF76B18087C94DBC6F262B2080B3F6C21F91A4D9D4BD091D052C09E88E191`
   - 退出码：`0`
3. 前端 ESLint
   - 命令：`npm run lint`（工作目录：`front`）
   - 输出：`> front@0.1.0 lint`、`> eslint`，无诊断行
   - 退出码：`0`
4. 前端生产构建
   - 命令：`npm run build`（工作目录：`front`）
   - 输出：`Compiled successfully`；静态路由生成 `14` 个
   - 退出码：`0`
5. 四个平台路由静态检查
   - 输出：`51job platformTabs=0 analysisTabs=0 headerAnalysisLinks=1`；`boss platformTabs=0 analysisTabs=0 headerAnalysisLinks=1`；`liepin platformTabs=0 analysisTabs=0 headerAnalysisLinks=1`；`zhilian platformTabs=0 analysisTabs=0 headerAnalysisLinks=1`
   - 退出码：`0`
6. 补丁反向预检查
   - 命令：`git apply --check --reverse --ignore-space-change --ignore-whitespace -- .codestable/features/2026-08-08-config-analysis-nav/config-analysis-nav.patch`
   - 输出：`PATCH_REVERSE_CHECK_EXIT=0`
   - 退出码：`0`
7. 回滚脚本解析与隔离副本回滚
   - 命令：`Parser::ParseFile`；`powershell -NoProfile -ExecutionPolicy Bypass -File .codestable/features/2026-08-08-config-analysis-nav/rollback-check/.codestable/features/2026-08-08-config-analysis-nav/rollback-config-analysis-nav.ps1 -Root .codestable/features/2026-08-08-config-analysis-nav/rollback-check`
   - 输出：`ROLLBACK_SCRIPT_PARSE_OK`、`ROLLBACK_OK`、`ROLLBACK_HASHES_OK`、`ROLLBACK_SCRIPT_EXIT=0`
   - 退出码：`0`

## 回滚结果

- 隔离副本四个目标文件均由修改态恢复为基线快照。
- 回滚脚本先校验修改态哈希，再复制基线，最后复核基线哈希。

## 结果

- 顶部配置/分析入口保留，底部重复按钮和内嵌分析组件均已移除。
- 主工作区仍保留修改态，回滚验证在独立副本完成。
