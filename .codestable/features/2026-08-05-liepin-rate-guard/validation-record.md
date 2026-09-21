---
doc_type: feature-validation
feature: liepin-rate-guard
date: 2026-08-05
status: verified
---

## Baseline

- Target-file SHA256 baseline saved before implementation: `C:\tmp\get_jobs_rate_guard_baseline.txt`
- Pre-existing targeted diff saved before implementation: `C:\tmp\get_jobs_rate_guard_preexisting.diff`
- The worktree already contained unrelated and earlier APP changes; this feature patch was generated against the reconstructed pre-change state and does not revert them.

## Changed behavior

- Search, pagination, detail navigation, and send actions share one account-level clock.
- Defaults converge to 10 jobs per run and 90-180 seconds between sends.
- Five successful sends trigger a 1200-1800 second batch cooldown.
- Search responses with status 403 or 429 stop the current task and emit a progress message.
- Legacy saved values below the floor are normalized by the worker and on configuration save.

## Commands and literal results

- `JAVA_HOME=D:\DevTools\jdk-17 .\gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinRateGuardTest` -> exit `1`; literal result: `JAVA_HOME is set to an invalid directory`.
- `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinRateGuardTest` -> `BUILD SUCCESSFUL`, exit `0`.
- `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon` -> `BUILD SUCCESSFUL`, exit `0`.
- `pnpm exec eslint app/liepin/page.tsx` from `front` -> no output, exit `0`.
- `pnpm build` from `front` -> `Compiled successfully`, `Generating static pages (13/13)`, exit `0`.
- `git diff --check -- <target files>` -> no output, exit `0`.
- `git apply --ignore-space-change --ignore-whitespace --check rate-guard.patch` in a reconstructed pre-change tree -> `full patch apply check: PASS`, exit `0`.
- Forward and reverse patch checks in the temporary verification tree -> `forward/reverse patch checks: PASS`, exit `0`.

## Rollback verification

- `rollback-rate-guard.ps1` performs a reverse `git apply --check` before applying the reverse patch.
- The reverse patch is scoped to the feature target files and leaves unrelated worktree changes untouched.
- Executed in a temporary copy: literal output `Rollback completed: rate guard changes removed; pre-existing worktree changes preserved.`; exit `0`.

## CodeStable impact

- Added a closed `ff` record for this small implementation; no project spec was changed.
