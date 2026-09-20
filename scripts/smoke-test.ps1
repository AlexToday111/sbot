param(
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$Restart
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$apiKey = $env:INTERNAL_API_KEY
if (-not $apiKey) {
    $keyLine = Get-Content -LiteralPath (Join-Path $projectDirectory '.env') |
        Where-Object { $_ -match '^INTERNAL_API_KEY=' } | Select-Object -First 1
    if ($keyLine) { $apiKey = ($keyLine -split '=', 2)[1] }
}
if (-not $apiKey) { throw 'Set INTERNAL_API_KEY or configure it in .env.' }

# Each run gets an isolated synthetic identity; no real Telegram account is contacted.
$testTelegramId = 900000000000000L + (Get-Random -Minimum 1 -Maximum 1000000000)
$headers = @{ 'X-Api-Key' = $apiKey; 'X-Telegram-User-Id' = [string]$testTelegramId }

function Api($Method, $Path, $Body = $null, $RequestKey = $null) {
    $requestHeaders = $headers.Clone()
    if ($RequestKey) { $requestHeaders['Idempotency-Key'] = $RequestKey }
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $requestHeaders }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 8 -Compress
    }
    try { Invoke-RestMethod @parameters }
    catch { throw "$Method $Path failed: $($_.Exception.Message) $($_.ErrorDetails.Message)" }
}

function Assert-Value($Condition, $Message) {
    if (-not $Condition) { throw $Message }
}

function Wait-Healthy {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $health = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'Application did not become healthy.'
}

Wait-Healthy
$registered = Api POST '/api/me' @{ firstName = 'Smoke Test'; username = 'smoke_test' }
$benchCatalog = Api GET '/api/exercises?q=Bench%20Press'
$shoulderCatalog = Api GET '/api/exercises?q=Shoulder%20Press'
$bench = $benchCatalog | Where-Object { $_.name -eq 'Bench Press' }
$shoulder = $shoulderCatalog | Where-Object { $_.name -eq 'Shoulder Press' }
Assert-Value ($null -ne $bench.id -and $null -ne $shoulder.id) 'Catalog lookup did not return exercise IDs.'
$template = Api POST '/api/workouts' @{
    name = 'Push Day'; exercises = @(
        @{ exerciseId = $bench.id; sets = 3; reps = 8; weight = 70 },
        @{ exerciseId = $shoulder.id; sets = 3; reps = 10; weight = 20 }
    )
}
$session = Api POST '/api/sessions' @{ templateId = $template.id } 'start-1'
for ($index = 0; $index -lt 3; $index++) {
    $null = Api POST "/api/sessions/$($session.id)/sets" @{
        sessionExerciseId = $session.exercises[0].id; weight = 70; repetitions = 8
    } "bench-$index"
}
foreach ($index in 0..2) {
    $repetitions = if ($index -eq 2) { 8 } else { 10 }
    $null = Api POST "/api/sessions/$($session.id)/sets" @{
        sessionExerciseId = $session.exercises[1].id; weight = 20; repetitions = $repetitions
    } "shoulder-$index"
}
$retried = Api POST "/api/sessions/$($session.id)/sets" @{
    sessionExerciseId = $session.exercises[1].id; weight = 20; repetitions = 8
} 'shoulder-2'
Assert-Value ($retried.summary.sets -eq 6) 'Duplicate request recorded an extra set.'
$completed = Api POST "/api/sessions/$($session.id)/finish"
Assert-Value ($completed.summary.volume -eq 2240) 'Unexpected total training volume.'
Assert-Value ($completed.summary.repetitions -eq 52) 'Unexpected repetition count.'

$active = Api POST "/api/sessions/$($session.id)/repeat" $null 'repeat-1'
$null = Api POST "/api/sessions/$($active.id)/sets" @{
    sessionExerciseId = $active.exercises[0].id; weight = 72.5; repetitions = 8
} 'active-set'
if ($Restart) {
    Push-Location $projectDirectory
    try {
        docker compose restart app
        if ($LASTEXITCODE -ne 0) { throw 'Compose restart failed.' }
    } finally { Pop-Location }
    Wait-Healthy
}
$recovered = Api GET '/api/sessions/active'
Assert-Value ($recovered.id -eq $active.id -and $recovered.summary.sets -eq 1) 'Active workout did not survive.'
$history = @(Api GET '/api/history')
Assert-Value ($history.id -contains $session.id) 'Completed workout is absent from history.'
$progress = Api GET "/api/exercises/$($bench.id)/progress?period=year"
Assert-Value ($progress.records.maxWeight -eq 70) 'Active workout incorrectly changed completed records.'
$null = Api POST "/api/sessions/$($active.id)/cancel"
$openapi = Invoke-RestMethod "$BaseUrl/v3/api-docs"
Assert-Value ($null -ne $openapi.paths.'/api/sessions/{id}/sets') 'OpenAPI is missing set recording.'

Write-Output "PASS: six sets, 52 reps, 2240 kg; retry deduplication, active recovery, history and records."
Write-Output "Synthetic Telegram identity: $testTelegramId (internal user $($registered.id)). Test data is retained."
