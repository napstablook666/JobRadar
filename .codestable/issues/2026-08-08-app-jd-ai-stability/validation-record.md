---
doc_type: issue-validation
issue: app-jd-ai-stability
date: 2026-08-08
status: verified
---

# 验证记录

## 基线与产物哈希

- 基线目录：`.codestable/features/2026-08-08-app-jd-ai-stability/baseline/`
- 基线哈希清单：`.codestable/features/2026-08-08-app-jd-ai-stability/baseline-hashes.json`
- 修改后 `AiService.java`: `6DD27FDDA3EBC7AACE86CC61A38C8551052AD9D9456BC251803C3DC31FCC77D`
- 修改后 `Job51Service.java`: `F0CB03E14288F4ACA95513A5C2BC97530D026E48D0D60D6490655D0DF658C058`
- 修改后 `Job51.java`: `3E1E94507C256AFECF11F1A9A0887133055CCC898931CC3CA2916C88FB5A254A`
- 修改后 `Job51Locators.java`: `F1BA2D0527EEB4852C43FD5CC74AD4B72FE418B945CA7B282F6B6E641712CDC6`
- 修改后测试哈希：`AiPromptTest.java=4E834B664CE3F6C3039B6DB0B079AAC1BA871194E892F856D3360E23EF4C82AA`、`Job51BehaviorTest.java=1C293971302EF66E91FD3989BAAD2EDF947CAA86736F71C5638920437F7AB85A`、`Job51SearchJsonTest.java=ACA900E1F737E9680B6AA324E1AB417DF644771BDB6D7361B3C3168F092EE12C`
- 补丁：`app-jd-ai-stability.patch`，`80B09744BD308D6E9446125E90E6A058A6E5AB99D0EAF8DE4866AE37FBD0DC8A`
- 回滚脚本：`rollback-app-jd-ai-stability.ps1`，`69F4AFF43DC28229C48FA2180C13BE6082E36511157481CD84BBA924270809F3`

## 测试与构建

Command: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests com.getjobs.application.service.AiPromptTest --tests com.getjobs.application.service.Job51SearchJsonTest --tests com.getjobs.worker.job51.Job51BehaviorTest`

Exit code: `0`

Literal output: `BUILD SUCCESSFUL in 10s`

Command: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test`

Exit code: `0`

Literal output: `BUILD SUCCESSFUL in 11s`

Command: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat build`

Exit code: `0`

Literal output: `BUILD SUCCESSFUL in 14s`

Command: `git diff --check -- src/main/java/com/getjobs/application/service/AiService.java src/main/java/com/getjobs/application/service/Job51Service.java src/main/java/com/getjobs/worker/job51/Job51.java src/test/java/com/getjobs/application/service/AiPromptTest.java src/test/java/com/getjobs/application/service/Job51SearchJsonTest.java src/test/java/com/getjobs/worker/job51/Job51BehaviorTest.java`

Exit code: `0`

## 补丁回放

Command: `git apply --check --recount --whitespace=nowarn --directory=target/local-run/app-jd-ai-stability-patch-check-v2 .codestable/issues/2026-08-08-app-jd-ai-stability/app-jd-ai-stability.patch`

Exit code: `0`

Command: `git apply --recount --whitespace=nowarn --directory=target/local-run/app-jd-ai-stability-patch-check-v2 .codestable/issues/2026-08-08-app-jd-ai-stability/app-jd-ai-stability.patch`

Exit code: `0`

Literal output: `PATCH_CONTENT_ALL_MATCH True`；7 个源码/测试文件逐项 `PATCH_CONTENT_CHECK ...=True`。

## 回滚回放

Command: `& .codestable/issues/2026-08-08-app-jd-ai-stability/rollback-app-jd-ai-stability.ps1 -Root target/local-run/app-jd-ai-stability-rollback-check-v3`

Exit code: `0`

Literal output: 6 个文件 `HASH_MATCH ...=True`，`ROLLBACK_NEW_TEST_REMOVED True`，`ROLLBACK_ROOT target/local-run/app-jd-ai-stability-rollback-check-v3`。

## 结论

定向测试、全量 Java 测试、Gradle 构建、补丁回放和隔离回滚均通过。真实网站运行态尚未在本轮启动新任务，保留当前服务状态不变。

