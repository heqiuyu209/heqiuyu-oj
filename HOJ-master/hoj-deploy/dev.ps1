param(
    [ValidateSet('up', 'start', 'stop', 'status', 'logs', 'restart-frontend')]
    [string]$Action = 'up',
    [ValidateRange(30, 900)]
    [int]$TimeoutSeconds = 360
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'local-compose.ps1')

docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker is unavailable. Start Docker Desktop and try again.' }
$composeArgs = @(Get-HojComposeArguments -ProjectRoot $projectRoot)
& docker @composeArgs config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Invalid development configuration. Docker Compose 2.24.4 or later is required.' }

# Reuse enabled overlays, but never start the completed identity initializer.
$managedServices = @()
if ($Action -in @('start', 'stop')) {
    $config = (Invoke-HojDocker -Capture -Arguments ($composeArgs + @('config', '--format', 'json'))) | ConvertFrom-Json
    $managedServices = @(@('hoj-mysql', 'hoj-redis', 'hoj-nacos', 'hoj-rag',
        'hoj-backend', 'hoj-judgeserver', 'hoj-frontend') | Where-Object { $config.services.PSObject.Properties[$_] })
    foreach ($required in @('hoj-mysql', 'hoj-redis', 'hoj-nacos', 'hoj-backend', 'hoj-judgeserver', 'hoj-frontend')) {
        if ($required -notin $managedServices) { throw "Required local service is missing from Compose: $required" }
    }
}

# Share the image updater's lock so routine lifecycle operations cannot race it.
$lock = $null
if ($Action -in @('start', 'stop')) {
    $lockPath = Join-Path ([IO.Path]::GetTempPath()) 'hoj-master-local-update.lock'
    $lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
}
try {
switch ($Action) {
    'start' {
        # Inspect the entire set before starting anything. Initial deployment and
        # configuration reconciliation belong to up; normal start preserves IDs.
        $states = @{}
        foreach ($name in $managedServices) {
            try { $states[$name] = Get-HojContainer $name }
            catch { throw "Existing service $name is unavailable. Use dev.ps1 up for initial deployment. $($_.Exception.Message)" }
        }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        foreach ($name in $managedServices) {
            if (!$states[$name].running) { Invoke-HojDocker -Arguments @('start', $states[$name].id) }
            while ($true) {
                $current = Get-HojContainer $name
                if ($current.id -ne $states[$name].id -or $current.image -ne $states[$name].image) {
                    throw "Service identity changed while starting: $name"
                }
                if (!$current.running) { throw "Service exited while starting: $name. Inspect dev.ps1 status and logs." }
                $ready = $false
                if ($name -eq 'hoj-backend') {
                    try {
                        $response = Invoke-RestMethod -Uri 'http://127.0.0.1:6688/api/get-website-config' -TimeoutSec 3
                        $ready = $response.status -eq 200
                    } catch { }
                } else {
                    $ready = [string]::IsNullOrWhiteSpace($current.health) -or $current.health -eq 'healthy'
                }
                if ($ready) { Write-Host "Ready: $name"; break }
                if ($watch.Elapsed.TotalSeconds -ge $TimeoutSeconds) { throw "Timed out waiting for $name. Inspect dev.ps1 status and logs." }
                Start-Sleep -Seconds 2
            }
        }
        $websiteReady = $false
        while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            try {
                $response = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/get-website-config' -TimeoutSec 3
                if ($response.status -eq 200) { $websiteReady = $true; break }
            } catch { }
            Start-Sleep -Seconds 2
        }
        if (!$websiteReady) { throw 'Services started, but the frontend website API is not ready.' }
        Write-Host 'OJ development environment: http://localhost:8080/home'
    }
    'up' {
        & docker @composeArgs up -d --no-build --wait --wait-timeout 360
        if ($LASTEXITCODE -ne 0) { throw 'Some services did not start. Use dev.ps1 status and dev.ps1 logs to inspect.' }
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            try {
                $response = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/get-website-config' -TimeoutSec 5
                if ($response.status -eq 200) { $ready = $true; break }
            } catch { }
            Start-Sleep -Seconds 2
        }
        if (!$ready) { throw 'Containers started, but the website API is not ready. Check the backend service.' }
        Write-Host 'OJ development environment: http://localhost:8080/home'
        Write-Host 'Saving Vue/CSS/JS files updates the browser automatically. No image build is needed.'
    }
    'stop' { Invoke-HojDocker -Arguments ($composeArgs + @('stop', '--timeout', '60') + $managedServices) }
    'status' { & docker @composeArgs ps -a }
    'logs' { & docker @composeArgs logs --tail 80 -f hoj-frontend }
    'restart-frontend' { & docker @composeArgs restart hoj-frontend }
}
if ($LASTEXITCODE -ne 0) { throw "Docker command failed ($LASTEXITCODE)." }
} finally {
    if ($lock) { $lock.Dispose() }
}
