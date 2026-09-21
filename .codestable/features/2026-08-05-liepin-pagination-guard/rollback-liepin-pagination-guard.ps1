param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
} else {
    $Root = (Resolve-Path -LiteralPath $Root).Path
}

$source = Join-Path $Root 'src\main\java\com\getjobs\worker\liepin\Liepin.java'
$expectedCurrentHash = '3CB1D8E86ECD6B5A374DF7ED35CB58714798CDEEEB28481F05305B85133E4251'
$expectedRollbackHash = '720506592BCE6F73B9C116A08AA4FEEE10E2836EC58F8CA3394D4382CE25A974'

function Assert-Hash([string]$Path, [string]$Expected) {
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
    if ($actual -ne $Expected) {
        throw "Rollback guard stopped: current file changed: $Path"
    }
}

function Replace-Once([string]$Text, [string]$From, [string]$To, [string]$Name) {
    $count = ([regex]::Matches($Text, [regex]::Escape($From))).Count
    if ($count -ne 1) {
        throw "Rollback guard stopped: expected one $Name block, found $count"
    }
    return $Text.Replace($From, $To)
}

Assert-Hash $source $expectedCurrentHash
$crlf = [string][char]13 + [char]10
$lf = [string][char]10
$text = [System.IO.File]::ReadAllText($source).Replace($crlf, $lf)

$newSearch = @'
        navigateAndCaptureSearchResponse(getSearchUrl() + "&key=" + cleanKeyword);

        if (!waitForSearchResults()) {
            info(String.format("【%s】搜索结果未加载出岗位卡片，跳过本次关键词", cleanKeyword));
            return;
        }

        // 分页控件只决定是否继续翻页，不作为搜索结果加载成功的必要条件。
        maxPage = 1;
        Locator paginationBox = findPaginationBox();
        if (paginationBox != null) {
            setMaxPage(paginationBox.locator("li"));
        } else {
            info(String.format("【%s】未发现分页控件，按当前结果页处理", cleanKeyword));
        }
'@
$oldSearch = @'
        page.navigate(getSearchUrl() + "&key=" + cleanKeyword);
        
        // 等待分页元素加载
        page.waitForSelector(PAGINATION_BOX, new Page.WaitForSelectorOptions().setTimeout(10000));
        Locator paginationBox = page.locator(PAGINATION_BOX);
        Locator lis = paginationBox.locator("li");
        setMaxPage(lis);
'@
$newLoop = @'
            paginationBox = findPaginationBox();
            if (paginationBox == null) {
                break;
            }
'@
$oldLoop = @'
            paginationBox = page.locator(PAGINATION_BOX);
'@
$newHelpers = @'
    /** 等待搜索结果卡片或任一分页变体出现；单页结果可以没有分页控件。 */
    private boolean waitForSearchResults() {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (page.locator(JOB_CARDS).count() > 0 || findPaginationBox() != null) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("检查猎聘搜索结果状态失败: {}", e.getMessage());
                return false;
            }
            page.waitForTimeout(200);
        }
        return false;
    }

    private Locator findPaginationBox() {
        String[] selectors = {PAGINATION_BOX, ".ant-pagination", "ul.ant-pagination"};
        for (String selector : selectors) {
            Locator candidate = page.locator(selector);
            if (candidate.count() > 0) {
                return candidate.first();
            }
        }
        return null;
    }

'@
$newSearch = $newSearch.Replace($crlf, $lf)
$oldSearch = $oldSearch.Replace($crlf, $lf)
$newLoop = $newLoop.Replace($crlf, $lf)
$oldLoop = $oldLoop.Replace($crlf, $lf)
$newHelpers = $newHelpers.Replace($crlf, $lf)
$text = Replace-Once $text $newSearch $oldSearch 'search result wait'
$text = Replace-Once $text $newLoop $oldLoop 'pagination loop'
$text = Replace-Once $text $newHelpers '' 'pagination helper'
[System.IO.File]::WriteAllText($source, $text.Replace($lf, $crlf), (New-Object System.Text.UTF8Encoding($false)))
Assert-Hash $source $expectedRollbackHash
Write-Output 'Rollback completed: pagination guard removed; unrelated Liepin changes preserved.'
