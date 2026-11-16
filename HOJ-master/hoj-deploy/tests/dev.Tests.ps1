$ErrorActionPreference = 'Stop'
$deploy = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('hoj-dev-test-' + [guid]::NewGuid().ToString('N'))
$app = Join-Path $testRoot 'app with spaces'
$entry = Join-Path $app 'hoj-deploy/dev.ps1'
New-Item -ItemType Directory -Path (Split-Path -Parent $entry) -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $deploy 'dev.ps1'),(Join-Path $deploy 'local-compose.ps1') -Destination (Split-Path -Parent $entry)
$files = @('compose.yaml','compose.dev.yaml','compose.rag.yaml','compose.video.yaml','compose.rag-rerank.yaml','compose.rag-context.yaml')
foreach ($file in ($files + '.env.rag.local')) { Set-Content -LiteralPath (Join-Path $app $file) -Value '# fixture only' }
$services = @('hoj-mysql','hoj-redis','hoj-nacos','hoj-rag','hoj-backend','hoj-judgeserver','hoj-frontend')
function Assert-Test($Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function global:Start-Sleep { }
function global:Invoke-RestMethod {
    $f=$global:Fixture
    $uri=@($args | Where-Object { $_ -like 'http://*' })[0]
    Assert-Test ($uri -in @('http://127.0.0.1:6688/api/get-website-config','http://127.0.0.1:8080/api/get-website-config')) 'Unexpected HTTP request'
    $f.httpCalls++
    if ($uri -like '*:6688/*') {
        $f.backendChecks++
        if ($f.backendChecks -eq 1) { return @{status=503} }
        $f.backendReady=$true
    }
    return @{status=200}
}
function global:docker {
    $a=@($args);$f=$global:Fixture;$global:LASTEXITCODE=0
    [void]$f.calls.Add($a)
    if ($a -contains '--format') {
        $position=[array]::IndexOf($a,'--format')
        Assert-Test (!$a[$position+1].Contains('"')) 'Native format requires embedded quotes'
    }
    if ($a[0] -eq 'info') { return '29.0.1' }
    if ($a[0] -eq 'ps') { return ($f.rows.Values.Id -join "`n") }
    if ($a[0] -eq 'inspect') {
        if ($a[2] -eq '{{json .Config.Labels}}') {
            $enabled=@($files)
            if ($f.mode -eq 'unknown-overlay') { $enabled += 'unexpected.yaml' }
            return @{'com.docker.compose.project.config_files'=(($enabled | ForEach-Object {Join-Path $app $_}) -join ',')} | ConvertTo-Json -Compress
        }
        Assert-Test ($a[2] -eq '{{json .}}') 'Unexpected inspect format'
        $row=@($f.rows.Values | Where-Object { $_.Id -eq $a[-1] -or "hoj-master-$($_.Config.Labels.'com.docker.compose.service')-1" -eq $a[-1] })[0]
        if (!$row) { $global:LASTEXITCODE=1;return }
        return $row | ConvertTo-Json -Depth 12 -Compress
    }
    if ($a[0] -eq 'start') {
        $row=@($f.rows.Values | Where-Object { $_.Id -eq $a[-1] })[0]
        Assert-Test ($null -ne $row) 'Unknown container started'
        $name=$row.Config.Labels.'com.docker.compose.service'
        Assert-Test ($name -ne 'hoj-rag-identity') 'Completed initializer started'
        if ($name -eq 'hoj-judgeserver') { Assert-Test $f.backendReady 'Started judge before backend readiness' }
        [void]$f.started.Add($name);$row.State.Running=$true
        return
    }
    Assert-Test ($a[0] -eq 'compose') 'Unexpected Docker mutation'
    $action=@($a | Where-Object {$_ -in @('config','stop')})[0]
    if ($action -eq 'config') {
        if ($a[-1] -eq 'json') { return $f.config | ConvertTo-Json -Depth 12 -Compress }
        return
    }
    Assert-Test ($action -eq 'stop') 'Unexpected Compose action'
    $position=[array]::IndexOf($a,'stop')
    $selected=@($a[($position+3)..($a.Count-1)])
    Assert-Test (($selected -join ',') -eq ($services -join ',')) 'Stop selection omitted a service or included the initializer'
    foreach ($name in $selected) { $f.rows[$name].State.Running=$false }
}
function Run-Test([string]$Mode, [string]$Action, [bool]$ShouldFail) {
    $global:Fixture=@{mode=$Mode;rows=@{};config=@{services=@{}};calls=[Collections.Generic.List[object]]::new();started=[Collections.Generic.List[string]]::new();backendChecks=0;backendReady=$false;httpCalls=0}
    $f=$global:Fixture
    foreach ($name in ($services + 'hoj-rag-identity')) {
        $state=@{Running=($Mode -eq 'running' -and $name -ne 'hoj-rag-identity')}
        if ($name -ne 'hoj-backend') { $state.Health=@{Status='healthy'} }
        $f.rows[$name]=@{Id="original-$name";Image="image-$name";State=$state;Mounts=@();Config=@{Labels=@{'com.docker.compose.service'=$name;'com.docker.compose.project'='hoj-master'}}}
        $f.config.services[$name]=@{}
    }
    if ($Mode -eq 'missing') { $f.rows.Remove('hoj-frontend') }
    if ($Mode -eq 'wrong-owner') { $f.rows['hoj-frontend'].Config.Labels.'com.docker.compose.project'='other-project' }
    if ($Mode -eq 'missing-config') { $f.config.services.Remove('hoj-mysql') }
    $failure=$null
    try { & $entry -Action $Action -TimeoutSeconds 30 6>&1 | Out-Null } catch { $failure=$_ }
    Assert-Test (($null -ne $failure) -eq $ShouldFail) "$Mode failure expectation did not match: $failure"
    return $f
}
$passed=[Collections.Generic.List[string]]::new()
try {
    $f=Run-Test 'stopped' 'start' $false
    Assert-Test (($f.started -join ',') -eq ($services -join ',')) 'Dependency order changed'
    Assert-Test ($f.backendChecks -eq 2 -and $f.httpCalls -eq 3) 'Readiness feedback was skipped'
    Assert-Test (!$f.rows['hoj-rag-identity'].State.Running) 'Initializer was rerun'
    $passed.Add('starts seven existing services in dependency order and waits for backend readiness')
    $f=Run-Test 'running' 'start' $false
    Assert-Test ($f.started.Count -eq 0) 'Already-running containers restarted'
    $passed.Add('already-running start preserves all processes')
    $f=Run-Test 'running' 'stop' $false
    Assert-Test (@($f.rows.Values | Where-Object {$_.State.Running}).Count -eq 0) 'A service remained running'
    $passed.Add('stop includes RAG and excludes the completed initializer')
    foreach ($mode in @('missing','wrong-owner','unknown-overlay','missing-config')) {
        $f=Run-Test $mode 'start' $true
        Assert-Test (@($f.calls | Where-Object { $_ -contains 'start' -or $_ -contains 'stop' }).Count -eq 0) 'Invalid preflight mutated services'
        $passed.Add("$mode rejected before service mutation")
    }
    $f=Run-Test 'missing-config' 'stop' $true
    Assert-Test (@($f.calls | Where-Object { $_ -contains 'stop' }).Count -eq 0) 'Invalid configuration stopped services'
    $passed.Add('incomplete configuration cannot fall through to a whole-project stop')
    Write-Output ($passed | ConvertTo-Json)
    Write-Output "Passed $($passed.Count) lifecycle tests; no actual Docker process was invoked."
} finally {
    $resolved=[IO.Path]::GetFullPath($testRoot)
    $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or !(Split-Path -Leaf $resolved).StartsWith('hoj-dev-test-')) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
