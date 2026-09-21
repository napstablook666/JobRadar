# 分析页总岗位数修复验证记录

## 基线
- `front/app/liepin/analysis/AnalysisContent.tsx` SHA256：`FCBE58AC84D0F3F3DD7DCBFBFCCF88F123B45C80C5220F9467607C8BB9325D2C`
- `进度白板.md` SHA256：`F87015C706D96925D6543BD7E0B3DFAD15AD6085AA9F63322878C08A7E1857B4`
- 基线副本：`AnalysisContent.tsx.baseline`、`进度白板.md.baseline`

## 修改
- 修改动作：使用 `apply_patch` 将 `statuses` 初始值从 `["已投递"]` 调整为空数组，并同步进度记录。
- `git diff --check -- front/app/liepin/analysis/AnalysisContent.tsx 进度白板.md`
- 字面输出：空输出；退出码 `0`。
- 修改后哈希：
  - `front/app/liepin/analysis/AnalysisContent.tsx`：`D4538B1E5BCBEC197BA5860A02E9152DE71163C8298D78EB4ECA618DC3BD45E4`
  - `进度白板.md`：`60EA54BF48604FE0E6D20757A48AD5ADBE0715E042E353E38962F901135E8A27`

## 验证命令与字面结果
- `pnpm exec eslint app/liepin/analysis/AnalysisContent.tsx`
  - 退出码 `0`；`0 errors`；保留 3 个既有 warning：`loadingStats` 未使用、两个 Hook 依赖提示。
- `pnpm run build`
  - 字面结果：`Compiled successfully`、`Generating static pages (13/13)`；退出码 `0`。
- `Invoke-WebRequest http://127.0.0.1:6866/liepin`
  - 字面结果：HTTP `200`；现有开发实例提供猎聘配置页，分析内容由该页内的标签切换展示。
- PowerShell 接口回归脚本
  - 字面输出：`{"allStats":{"total":3388,"delivered":21,"pending":3367},"allListTotal":3388,"deliveredStats":{"total":21,"delivered":21,"pending":0},"deliveredListTotal":21,"invariantAll":true,"invariantDelivered":true}`
  - 退出码 `0`。
- `Push-Location patch-check; git apply --check --unsafe-paths ../liepin-analysis-total.patch; Pop-Location`
  - 字面输出：空输出；退出码 `0`。
- `pwsh -NoProfile -ExecutionPolicy Bypass -File rollback-liepin-analysis-total.ps1 -Root rollback-check`
  - 字面输出：`Rollback completed: restored the analysis page baseline and progress board.`；退出码 `0`。

## 产物
- `liepin-analysis-total.patch`
- `rollback-liepin-analysis-total.ps1`
- `liepin-analysis-total-ff-note.md`
