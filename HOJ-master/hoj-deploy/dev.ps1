param(
    [ValidateSet('up', 'stop', 'status', 'logs', 'restart-frontend')]
    [string]$Action = 'up'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# Explicit project name reuses the existing local OJ containers and data volumes.
$composeArgs = @('compose', '--project-name', 'hoj-master',
    '-f', (Join-Path $projectRoot 'compose.yaml'),
    '-f', (Join-Path $projectRoot 'compose.dev.yaml'))

docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker is unavailable. Start Docker Desktop and try again.' }
& docker @composeArgs config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Invalid development configuration. Docker Compose 2.24.4 or later is required.' }

switch ($Action) {
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
    'stop' { & docker @composeArgs stop }
    'status' { & docker @composeArgs ps -a }
    'logs' { & docker @composeArgs logs --tail 80 -f hoj-frontend }
    'restart-frontend' { & docker @composeArgs restart hoj-frontend }
}
if ($LASTEXITCODE -ne 0) { throw "Docker command failed ($LASTEXITCODE)." }
