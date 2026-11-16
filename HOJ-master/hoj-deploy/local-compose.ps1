function Invoke-HojDocker {
    param([string[]]$Arguments, [switch]$Capture)
    if ($Capture) {
        $lines = & docker @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Docker $($Arguments[0]) failed ($LASTEXITCODE)." }
        return ($lines -join "`n")
    }
    & docker @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Docker $($Arguments[0]) failed ($LASTEXITCODE)." }
}

function Get-HojComposeArguments {
    param([string]$ProjectRoot)
    $allowed = @('compose.yaml', 'compose.dev.yaml', 'compose.rag.yaml',
        'compose.video.yaml', 'compose.rag-rerank.yaml', 'compose.rag-context.yaml')
    # Existing services are the authority for enabled overlays, even when stopped.
    # Read labels as JSON so Windows PowerShell 5.1 never has to pass embedded
    # quote characters in a native Go-template argument.
    $containers = Invoke-HojDocker -Capture -Arguments @('ps', '-a', '--quiet', '--no-trunc',
        '--filter', 'label=com.docker.compose.project=hoj-master')
    $ids = @($containers -split '\r?\n' | Where-Object { ![string]::IsNullOrWhiteSpace($_) })
    $existing = @()
    if ($ids.Count) {
        $labelRows = Invoke-HojDocker -Capture -Arguments (@('inspect', '--format', '{{json .Config.Labels}}') + $ids)
        foreach ($line in ($labelRows -split '\r?\n')) {
            $labels = $line | ConvertFrom-Json
            $existing += $labels.'com.docker.compose.project.config_files'
        }
    }
    $enabled = @('compose.yaml', 'compose.dev.yaml')
    foreach ($line in $existing) {
        foreach ($file in ($line -split ',')) {
            if ([string]::IsNullOrWhiteSpace($file)) { continue }
            $name = ($file.Trim() -split '[\\/]')[-1]
            if ($name -notin $allowed) { throw "Unrecognized active Compose overlay: $name. Preserve it explicitly before updating." }
            if ($name -notin $enabled) { $enabled += $name }
        }
    }
    $result = @('compose', '--parallel', '1', '--project-name', 'hoj-master')
    if ('compose.rag.yaml' -in $enabled) {
        $envFile = Join-Path $ProjectRoot '.env.rag.local'
        if (!(Test-Path -LiteralPath $envFile -PathType Leaf)) { throw 'The enabled RAG stack requires .env.rag.local.' }
        $result += @('--env-file', $envFile)
    }
    foreach ($name in $allowed) {
        if ($name -in $enabled) {
            $path = Join-Path $ProjectRoot $name
            if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing Compose file: $name" }
            $result += @('-f', $path)
        }
    }
    return $result
}

function Get-HojContainer {
    param([string]$Service)
    # Capture privately: the full inspect result can contain credentials. Only
    # the selected non-secret fields below leave this function.
    $row = (Invoke-HojDocker -Capture -Arguments @('inspect', '--format', '{{json .}}', "hoj-master-$Service-1")) | ConvertFrom-Json
    $labels = $row.Config.Labels
    if ($labels.'com.docker.compose.service' -ne $Service -or
        $labels.'com.docker.compose.project' -ne 'hoj-master') { throw "Unexpected container ownership: $Service" }
    return [pscustomobject]@{
        id = $row.Id
        image = $row.Image
        running = $row.State.Running
        health = [string]$row.State.Health.Status
        mounts = $row.Mounts
        service = $labels.'com.docker.compose.service'
        project = $labels.'com.docker.compose.project'
    }
}

function Get-HojNamedMounts {
    param($Container)
    return @($Container.mounts | Where-Object { $_.Type -eq 'volume' } |
        ForEach-Object { "$($_.Destination)|$($_.Name)|$($_.RW)" } | Sort-Object)
}
