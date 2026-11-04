$ErrorActionPreference = 'Stop'
$deploy = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('hoj-update-test-' + [guid]::NewGuid().ToString('N'))
$app = Join-Path $testRoot 'app'
$entry = Join-Path $app 'hoj-deploy/update.ps1'
New-Item -ItemType Directory -Path (Split-Path -Parent $entry) -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $deploy 'update.ps1'),(Join-Path $deploy 'local-compose.ps1') -Destination (Split-Path -Parent $entry)
foreach ($file in @('compose.yaml','compose.dev.yaml','compose.rag.yaml','compose.video.yaml','compose.rag-rerank.yaml','compose.rag-context.yaml','.env.rag.local')) {
    Set-Content -LiteralPath (Join-Path $app $file) -Value '# fixture only'
}

function Assert-Test($Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function global:Invoke-RestMethod {
    Assert-Test ($args -contains 'http://127.0.0.1:6688/api/get-website-config') 'unexpected HTTP request'
    return @{status=200}
}
function global:Start-Sleep { }
function global:docker {
    $a = @($args)
    $global:LASTEXITCODE = 0
    [void]$global:Fixture.calls.Add($a)
    $f = $global:Fixture
    if ($a[0] -eq 'info') { return '29.0.1' }
    if ($a[0] -eq 'ps') {
        if ($f.mode -eq 'unknown-overlay') { return (Join-Path $app 'unknown.yaml') }
        return (@('compose.yaml','compose.dev.yaml','compose.rag.yaml','compose.video.yaml','compose.rag-rerank.yaml','compose.rag-context.yaml') | ForEach-Object { Join-Path $app $_ }) -join ','
    }
    if ($a[0] -eq 'inspect') {
        $row = @($f.rows.Values | Where-Object { $_.id -eq $a[-1] -or "hoj-master-$($_.service)-1" -eq $a[-1] })[0]
        if (!$row) { $global:LASTEXITCODE=1; return }
        if ($a[2] -like '*Config.Env*') { return '["BASE=base","SETTING=value"]' }
        if ($a[2] -like '*State.Health*') {
            if ($f.mode -eq 'resume-fail' -and $f.resumed) { $global:LASTEXITCODE=1; return }
            return 'healthy'
        }
        return $row | ConvertTo-Json -Depth 12 -Compress
    }
    if ($a[0] -eq 'image') {
        if ($a[1] -eq 'tag') { $f.tags[$a[3]]=$f.tags[$a[2]]; return }
        if ($a[1] -eq 'inspect') {
            if ($a[3] -like '*Config.Env*') { return '["BASE=base"]' }
            return $f.tags[$a[-1]]
        }
    }
    if ($a[0] -eq 'start' -or $a[0] -eq 'stop') {
        $row = @($f.rows.Values | Where-Object { $_.id -eq $a[-1] })[0]
        Assert-Test ($null -ne $row) 'attempted unknown container action'
        $row.running=($a[0] -eq 'start')
        if ($row.service -eq 'hoj-rag' -and $row.running) { $f.resumed=$true }
        return
    }
    Assert-Test ($a[0] -eq 'compose') ('unexpected docker command: '+($a -join ' '))
    $action = @($a | Where-Object { $_ -in @('config','stop','build','up') })[0]
    if ($action -eq 'config') {
        if ($a[-1] -eq 'json') { return $f.config | ConvertTo-Json -Depth 12 -Compress }
        return
    }
    $service=$a[-1]
    if ($action -eq 'stop') { $f.rows[$service].running=$false; return }
    if ($action -eq 'build') {
        $f.buildCount++
        if ($service -eq 'hoj-rag-identity') { $service='hoj-rag' }
        $tag=$f.config.services[$service].image
        $f.tags[$tag]="new-$service"
        if ($f.mode -in @('build-fail','resume-fail') -or ($f.mode -eq 'build-second-fail' -and $f.buildCount -eq 2)) { $global:LASTEXITCODE=17 }
        return
    }
    Assert-Test ($action -eq 'up') 'unknown Compose action'
    $f.upCount++
    $row=$f.rows[$service]
    $row.id="replacement-$service-$($f.upCount)"
    $row.image=$f.tags[$f.config.services[$service].image]
    $row.running=$true
    if ($f.mode -eq 'up-all-fail' -or ($f.mode -eq 'up-fail' -and $f.upCount -eq 1)) { $global:LASTEXITCODE=19 }
}

function New-Fixture([string]$Mode) {
    $global:Fixture=@{mode=$Mode;calls=[Collections.Generic.List[object]]::new();rows=@{};tags=@{};buildCount=0;upCount=0;resumed=$false;config=@{services=@{};volumes=@{data=@{name='hoj-master_data'}}}}
    foreach ($name in @('hoj-backend','hoj-judgeserver','hoj-rag')) {
        $image="old-$name"
        $global:Fixture.rows[$name]=@{id="original-$name";image=$image;running=$true;service=$name;project='hoj-master';mounts=@(@{Type='volume';Destination='/data';Name='hoj-master_data';RW=$true})}
        $global:Fixture.tags[$image]=$image
        $global:Fixture.tags["${name}:local"]=$image
        $global:Fixture.config.services[$name]=@{image="${name}:local";build=@{context='.'};environment=@{SETTING='value'};volumes=@(@{type='volume';source='data';target='/data';read_only=$false})}
    }
    $global:Fixture.config.services['hoj-rag'].Remove('build')
    $global:Fixture.config.services['hoj-rag-identity']=@{image='hoj-rag:local';build=@{context='.'}}
    if ($Mode -eq 'env-changed') { $global:Fixture.config.services['hoj-backend'].environment.SETTING='changed' }
    if ($Mode -eq 'up-all-fail') { $global:Fixture.rows['hoj-backend'].running=$false }
    if ($Mode -eq 'up-fail') { $global:Fixture.tags['hoj-backend:local']='stale-tag-not-running-image' }
}

function Run-Test([string]$Mode, [string[]]$Services, [bool]$Pause, [bool]$Planning, [bool]$ShouldFail) {
    New-Fixture $Mode
    $failure=$null
    $messages=@()
    Assert-Test ((Get-Command docker).CommandType -eq 'Function') 'Docker substitute is not active'
    try { & $entry -Service $Services -PauseRag:$Pause -Plan:$Planning -WaitTimeoutSeconds 30 6>&1 3>&1 | ForEach-Object { $messages += [string]$_ } }
    catch { $failure=$_ }
    Assert-Test (($null -ne $failure) -eq $ShouldFail) "$Mode failure expectation did not match: $failure"
    return @{error=$failure;messages=($messages -join "`n")}
}

$passed=[Collections.Generic.List[string]]::new()
try {
    Run-Test 'plan' @('hoj-backend') $true $true $false | Out-Null
    Assert-Test (@($global:Fixture.calls | Where-Object { $_ -contains 'build' -or $_ -contains 'stop' -or $_ -contains 'up' -or $_ -contains 'tag' }).Count -eq 0) 'Plan mutated Docker'
    $passed.Add('plan performs no Docker mutations')

    Run-Test 'success' @('hoj-backend','hoj-judgeserver') $true $false $false | Out-Null
    $calls=$global:Fixture.calls
    $firstBuild=-1; $lastStop=-1
    for ($i=0;$i -lt $calls.Count;$i++) { if ($calls[$i] -contains 'stop') {$lastStop=$i}; if ($firstBuild -lt 0 -and $calls[$i] -contains 'build') {$firstBuild=$i} }
    Assert-Test ($lastStop -lt $firstBuild) 'A selected service was stopped after a build began'
    foreach ($call in @($calls | Where-Object { $_ -contains 'build' -or $_ -contains 'up' })) {
        Assert-Test ($call -contains '1') 'Compose parallel limit missing'
        Assert-Test (@($call | Where-Object { $_ -like '*compose*.yaml' }).Count -eq 6) 'Enabled overlay lost'
    }
    Assert-Test $global:Fixture.resumed 'Paused RAG was not resumed'
    $passed.Add('all selected services stop before serial builds; six overlays and RAG resume retained')

    Run-Test 'build-fail' @('hoj-backend','hoj-judgeserver') $true $false $true | Out-Null
    Assert-Test (@($global:Fixture.calls | Where-Object { $_ -contains 'build' }).Count -eq 1) 'Updated a later service after failure'
    Assert-Test (@($global:Fixture.calls | Where-Object { $_ -contains 'up' }).Count -eq 0) 'Replaced a service after build failure'
    foreach ($name in @('hoj-backend','hoj-judgeserver','hoj-rag')) {
        Assert-Test ($global:Fixture.rows[$name].running -and $global:Fixture.rows[$name].image -eq "old-$name") 'Original service not restored'
    }
    $passed.Add('build failure restores original running containers and cancels later builds')

    Run-Test 'build-second-fail' @('hoj-backend','hoj-judgeserver') $false $false $true | Out-Null
    foreach ($name in @('hoj-backend','hoj-judgeserver')) {
        Assert-Test ($global:Fixture.tags["${name}:local"] -eq "old-$name" -and $global:Fixture.rows[$name].running) 'A successful earlier build tag was not restored'
    }
    Assert-Test ($global:Fixture.upCount -eq 0) 'Replaced a service despite later build failure'
    $passed.Add('second build failure restores tags already overwritten by an earlier build')

    Run-Test 'up-fail' @('hoj-backend') $false $false $true | Out-Null
    Assert-Test ($global:Fixture.rows['hoj-backend'].image -eq 'old-hoj-backend' -and $global:Fixture.rows['hoj-backend'].running) 'Overwritten image tag did not roll back'
    $passed.Add('failed replacement rolls back to the original image')

    Run-Test 'up-all-fail' @('hoj-backend') $false $false $true | Out-Null
    Assert-Test (!$global:Fixture.rows['hoj-backend'].running) 'Failed rollback left an initially stopped service running'
    $passed.Add('initially stopped state retained even when rollback readiness fails')

    $result=Run-Test 'resume-fail' @('hoj-backend') $true $false $true
    Assert-Test ($result.error.Exception.Message -like '*17*') 'RAG resume failure masked the build failure'
    Assert-Test ($result.messages -notlike '*Update succeeded*') 'Reported success before RAG recovery'
    $passed.Add('RAG resume failure preserves the primary build error and never reports success')

    Run-Test 'success' @('hoj-rag') $false $false $false | Out-Null
    Assert-Test (@($global:Fixture.calls | Where-Object { $_ -contains 'build' -and $_[-1] -eq 'hoj-rag-identity' }).Count -eq 1) 'Wrong RAG build target'
    Assert-Test (@($global:Fixture.calls | Where-Object { $_ -contains 'up' -and $_[-1] -eq 'hoj-rag-identity' }).Count -eq 0) 'Recreated identity service'
    $passed.Add('RAG builds the shared image without recreating the identity service')

    foreach ($mode in @('unknown-overlay','env-changed')) {
        Run-Test $mode @('hoj-backend') $false $false $true | Out-Null
        Assert-Test (@($global:Fixture.calls | Where-Object { $_ -contains 'stop' -or $_ -contains 'build' -or $_ -contains 'up' }).Count -eq 0) 'Invalid preflight mutated services'
        $passed.Add("$mode rejected before service mutation")
    }
    Write-Output ($passed | ConvertTo-Json)
    Write-Output "Passed $($passed.Count) lifecycle tests; no actual Docker process was invoked."
} finally {
    $resolved=[IO.Path]::GetFullPath($testRoot)
    $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or !(Split-Path -Leaf $resolved).StartsWith('hoj-update-test-')) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
