param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
)

$ErrorActionPreference = 'Stop'
$feature = Join-Path $RepoRoot '.codestable\features\2026-08-07-app-aggressive-interval'
$baseline = Join-Path $feature 'baseline'

$files = @(
    @{ Source = 'front__app__liepin__page.tsx'; Target = 'front\app\liepin\page.tsx' },
    @{ Source = 'src__main__java__com__getjobs__application__service__LiepinService.java'; Target = 'src\main\java\com\getjobs\application\service\LiepinService.java' },
    @{ Source = 'src__main__java__com__getjobs__worker__liepin__Liepin.java'; Target = 'src\main\java\com\getjobs\worker\liepin\Liepin.java' },
    @{ Source = 'src__main__java__com__getjobs__worker__liepin__LiepinConfig.java'; Target = 'src\main\java\com\getjobs\worker\liepin\LiepinConfig.java' },
    @{ Source = 'src__test__java__com__getjobs__worker__liepin__LiepinRateGuardTest.java'; Target = 'src\test\java\com\getjobs\worker\liepin\LiepinRateGuardTest.java' }
)

foreach ($file in $files) {
    $source = Join-Path $baseline $file.Source
    $target = Join-Path $RepoRoot $file.Target
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing baseline: $source" }
    Copy-Item -LiteralPath $source -Destination $target -Force
}

$jar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.xerial\sqlite-jdbc" -Recurse -Filter '*.jar' |
    Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
if (-not $jar) { throw 'sqlite-jdbc jar not found' }

Push-Location $RepoRoot
try {
    @'
import java.sql.*;
var c=DriverManager.getConnection("jdbc:sqlite:db/getjobs.db");
try {
  c.createStatement().execute("PRAGMA busy_timeout = 10000");
  c.setAutoCommit(false);
  var ps=c.prepareStatement("update liepin_config set min_delay_seconds=?, max_delay_seconds=?, search_min_delay_seconds=?, search_max_delay_seconds=?, page_min_delay_seconds=?, page_max_delay_seconds=?, detail_min_delay_seconds=?, detail_max_delay_seconds=?, rate_guard_batch_size=?, batch_cooldown_min_seconds=?, batch_cooldown_max_seconds=? where id=1");
  int[] values={120,240,60,120,30,60,45,90,5,1200,1800};
  for (int i=0;i<values.length;i++) ps.setInt(i+1, values[i]);
  int updated=ps.executeUpdate();
  if (updated != 1) throw new IllegalStateException("updated rows="+updated);
  c.commit();
  System.out.println("ROLLBACK_DB_OK");
} catch (Exception e) {
  try { c.rollback(); } catch (Exception ignored) {}
  throw e;
} finally { c.close(); }
'@ | jshell --class-path $jar -q
    if ($LASTEXITCODE -ne 0) { throw "Database rollback failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Output 'ROLLBACK_SOURCE_OK'
