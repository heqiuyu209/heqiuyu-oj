param(
    [ValidateSet('hoj-backend', 'hoj-judgeserver', 'hoj-rag')]
    [string[]]$Service = @('hoj-backend'),
    [switch]$PauseRag,
    [switch]$Plan,
    [ValidateRange(30, 900)][int]$WaitTimeoutSeconds = 360
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-compose.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$Service = @($Service | Select-Object -Unique)
Invoke-HojDocker -Capture -Arguments @('info', '--format', '{{.ServerVersion}}') | Out-Null
$composeArgs = @(Get-HojComposeArguments -ProjectRoot $projectRoot)
Invoke-HojDocker -Arguments ($composeArgs + @('config', '--quiet'))
# Never print or persist the merged environment / credentials.
$config = (Invoke-HojDocker -Capture -Arguments ($composeArgs + @('config', '--format', 'json'))) | ConvertFrom-Json
$states = @{}
$imageNames = @{}
foreach ($name in $Service) {
    $definition = $config.services.PSObject.Properties[$name]
    if (!$definition) { throw "Service is not enabled: $name" }
    $states[$name] = Get-HojContainer -Service $name
    $imageNames[$name] = [string]$definition.Value.image
    $builder = if ($name -eq 'hoj-rag') { 'hoj-rag-identity' } else { $name }
    $buildDefinition = $config.services.PSObject.Properties[$builder]
    if (!$buildDefinition -or !$buildDefinition.Value.build -or
        $buildDefinition.Value.image -ne $imageNames[$name]) { throw "No matching local image build for $name" }
    $expectedMounts = @($definition.Value.volumes | Where-Object { $_.type -eq 'volume' } | ForEach-Object {
        $volumeName = $config.volumes.PSObject.Properties[$_.source].Value.name
        "$($_.target)|$volumeName|$(-not $_.read_only)"
    } | Sort-Object)
    if ((Compare-Object @(Get-HojNamedMounts $states[$name]) $expectedMounts)) {
        throw "Named volumes differ for $name. Review configuration before updating."
    }
    $oldEnv = @{}
    foreach ($entry in ((Invoke-HojDocker -Capture -Arguments @('inspect', '--format', '{{json .Config.Env}}', $states[$name].id)) | ConvertFrom-Json)) {
        $pair = $entry -split '=', 2
        $oldEnv[$pair[0]] = $pair[1]
    }
    $expectedEnv = @{}
    foreach ($entry in ((Invoke-HojDocker -Capture -Arguments @('image', 'inspect', '--format', '{{json .Config.Env}}', $states[$name].image)) | ConvertFrom-Json)) {
        $pair = $entry -split '=', 2
        $expectedEnv[$pair[0]] = $pair[1]
    }
    foreach ($property in $definition.Value.environment.PSObject.Properties) { $expectedEnv[$property.Name] = [string]$property.Value }
    $changed = @(@($oldEnv.Keys) + @($expectedEnv.Keys) | Select-Object -Unique | Where-Object {
        !$oldEnv.ContainsKey($_) -or !$expectedEnv.ContainsKey($_) -or $oldEnv[$_] -cne $expectedEnv[$_]
    })
    if ($changed.Count) { throw "Environment configuration changed for $name. Apply configuration changes separately; this command updates source images." }
}
$paused = $null
if ($PauseRag -and 'hoj-rag' -notin $Service -and $config.services.PSObject.Properties['hoj-rag']) {
    $paused = Get-HojContainer -Service 'hoj-rag'
}
Write-Host "Services: $($Service -join ', '); serial build; data volumes retained."
if ($paused -and $paused.running) { Write-Host 'RAG will pause during the build and resume afterwards.' }
if ($Plan) {
    Write-Host 'Plan only: stop selected services, build each image, then replace and verify each service. No changes made.'
    return
}

# A held file handle prevents two updates from interleaving image tags and recovery.
$lockPath = Join-Path ([IO.Path]::GetTempPath()) 'hoj-master-local-update.lock'
$lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$backupTags = @{}
$stopped = @()
$attemptedReplacement = @()
$pausedByUs = $false
$updateFailure = $null
$resumeFailure = $null
try {
    foreach ($name in $Service) {
        $current = Get-HojContainer $name
        if ($current.id -ne $states[$name].id -or $current.image -ne $states[$name].image -or $current.running -ne $states[$name].running) {
            throw 'Service state changed after planning. Retry with the current state.'
        }
    }
    if ($paused) {
        $current = Get-HojContainer 'hoj-rag'
        if ($current.id -ne $paused.id -or $current.running -ne $paused.running) { throw 'RAG state changed after planning.' }
    }
    foreach ($name in $Service) {
        # Tags share the existing image layers and consume no additional running memory.
        $backupTags[$name] = "hoj-update-backup/$name`:$([guid]::NewGuid().ToString('N'))"
        Invoke-HojDocker -Arguments @('image', 'tag', $states[$name].image, $backupTags[$name])
    }
    foreach ($name in $Service) {
        Write-Host "Stopping $name before building..."
        # Record intent first, so a timed-out stop still enters recovery.
        $stopped += $name
        Invoke-HojDocker -Arguments ($composeArgs + @('stop', '--timeout', '60', $name))
        if ((Get-HojContainer $name).running) { throw "Service did not stop: $name" }
    }
    if ($paused -and $paused.running) {
        $pausedByUs = $true
        Invoke-HojDocker -Arguments @('stop', '--time', '60', $paused.id)
        if ((Get-HojContainer 'hoj-rag').running) { throw 'RAG did not stop.' }
    }
    foreach ($name in $Service) {
        $builder = if ($name -eq 'hoj-rag') { 'hoj-rag-identity' } else { $name }
        Write-Host "Building $name (one image at a time)..."
        Invoke-HojDocker -Arguments ($composeArgs + @('build', $builder))
    }
    foreach ($name in $Service) {
        Write-Host "Replacing $name and waiting for readiness..."
        $attemptedReplacement += $name
        Invoke-HojDocker -Arguments ($composeArgs + @('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
            '--wait', '--wait-timeout', "$WaitTimeoutSeconds", $name))
        $after = Get-HojContainer $name
        $builtImage = Invoke-HojDocker -Capture -Arguments @('image', 'inspect', '--format', '{{.Id}}', $imageNames[$name])
        if (!$after.running -or $after.image -ne $builtImage) { throw "New image is not running: $name" }
        if (Compare-Object @(Get-HojNamedMounts $states[$name]) @(Get-HojNamedMounts $after)) { throw "Volume change after update: $name" }
        if ($name -eq 'hoj-backend') {
            $ready = $false
            $deadline = [DateTime]::UtcNow.AddSeconds($WaitTimeoutSeconds)
            do {
                try {
                    $response = Invoke-RestMethod -Uri 'http://127.0.0.1:6688/api/get-website-config' -TimeoutSec 5
                    if ($response.status -eq 200) { $ready = $true; break }
                } catch { }
                Start-Sleep -Seconds 2
            } while ([DateTime]::UtcNow -lt $deadline)
            if (!$ready) { throw 'Backend started but its website API did not become ready.' }
        }
    }
} catch {
    $failure = $_
    Write-Warning 'Update failed; restoring the previous image and running state.'
    $recoveryErrors = @()
    foreach ($name in $stopped) {
        try {
            if ($backupTags.ContainsKey($name)) {
                Invoke-HojDocker -Arguments @('image', 'tag', $backupTags[$name], $imageNames[$name])
            }
            if ($name -in $attemptedReplacement) {
                try {
                    Invoke-HojDocker -Arguments ($composeArgs + @('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                        '--wait', '--wait-timeout', "$WaitTimeoutSeconds", $name))
                } finally {
                    if (!$states[$name].running) { Invoke-HojDocker -Arguments ($composeArgs + @('stop', $name)) }
                }
            } elseif ($states[$name].running) {
                Invoke-HojDocker -Arguments @('start', $states[$name].id)
            }
            $restored = Get-HojContainer $name
            if ($restored.image -ne $states[$name].image -or $restored.running -ne $states[$name].running -or
                (Compare-Object @(Get-HojNamedMounts $states[$name]) @(Get-HojNamedMounts $restored))) {
                throw "Recovery state mismatch: $name"
            }
        } catch { $recoveryErrors += $name }
    }
    if ($recoveryErrors.Count) { Write-Warning "Recovery needs attention: $($recoveryErrors -join ', '). Previous images are retained." }
    $updateFailure = $failure
} finally {
    try {
        if ($pausedByUs) {
            Write-Host 'Resuming the original RAG container...'
            Invoke-HojDocker -Arguments @('start', $paused.id)
            $deadline = [DateTime]::UtcNow.AddSeconds($WaitTimeoutSeconds)
            do {
                $health = Invoke-HojDocker -Capture -Arguments @('inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{end}}', $paused.id)
                if ($health -eq 'healthy') { break }
                Start-Sleep -Seconds 2
            } while ([DateTime]::UtcNow -lt $deadline)
            if ($health -ne 'healthy') { throw 'The original RAG container did not become healthy after resume.' }
        }
    } catch { $resumeFailure = $_ }
    finally { $lock.Dispose() }
}
if ($updateFailure) {
    if ($resumeFailure) { Write-Warning "RAG resume also failed: $($resumeFailure.Exception.Message)" }
    throw $updateFailure
}
if ($resumeFailure) { throw $resumeFailure }
Write-Host 'Update succeeded. Previous image tags remain available for recovery; no volumes were removed.'
