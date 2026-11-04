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
    $existing = Invoke-HojDocker -Capture -Arguments @('ps', '-a',
        '--filter', 'label=com.docker.compose.project=hoj-master', '--format',
        '{{.Label "com.docker.compose.project.config_files"}}')
    $enabled = @('compose.yaml', 'compose.dev.yaml')
    foreach ($line in ($existing -split "`n")) {
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
    $format = '{"id":{{json .Id}},"image":{{json .Image}},"running":{{json .State.Running}},"mounts":{{json .Mounts}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"project":{{json (index .Config.Labels "com.docker.compose.project")}}}'
    $row = (Invoke-HojDocker -Capture -Arguments @('inspect', '--format', $format, "hoj-master-$Service-1")) | ConvertFrom-Json
    if ($row.service -ne $Service -or $row.project -ne 'hoj-master') { throw "Unexpected container ownership: $Service" }
    return $row
}

function Get-HojNamedMounts {
    param($Container)
    return @($Container.mounts | Where-Object { $_.Type -eq 'volume' } |
        ForEach-Object { "$($_.Destination)|$($_.Name)|$($_.RW)" } | Sort-Object)
}
