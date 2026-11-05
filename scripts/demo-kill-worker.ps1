<#
.SYNOPSIS
  Kills a worker in the middle of a workflow and shows that the workflow still finishes,
  with every finished step done exactly once.

.DESCRIPTION
  1. starts the Revenant server (unless one is already running) and a worker
  2. starts an order workflow: charge -> reserve stock -> ship (slow) -> send receipt
  3. kills the worker process without warning while it is shipping (the same as kill -9)
  4. starts a new worker; the server hands it the interrupted step once the dead worker's lease runs out
  5. checks the "ledger" file that the activities write: charge, reserve and receipt exactly once

  PostgreSQL (and optionally Redis and Kafka) must be running: docker compose up -d

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\demo-kill-worker.ps1
#>
[CmdletBinding()]
param(
    [string]$ServerUrl = "http://localhost:8080",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$onWindows = ($env:OS -eq "Windows_NT")
$logs = Join-Path $root "demo-logs"
$ledger = Join-Path $logs "ledger.txt"
$started = New-Object System.Collections.ArrayList

function Say($text, $color = "White") { Write-Host $text -ForegroundColor $color }
function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }

function Find-Java {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $candidate) { return $candidate }
        $candidate = Join-Path $env:JAVA_HOME "bin/java"
        if (Test-Path $candidate) { return $candidate }
    }
    $command = Get-Command java -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "Java 21 was not found. Install a JDK 21 or set JAVA_HOME."
}

function Test-Port($hostName, $port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $pending = $client.BeginConnect($hostName, $port, $null, $null)
        $connected = $pending.AsyncWaitHandle.WaitOne(1500)
        if ($connected) { $client.EndConnect($pending) }
        $client.Close()
        return $connected
    } catch { return $false }
}

function Test-Health {
    try {
        $health = Invoke-RestMethod -Uri "$ServerUrl/actuator/health" -TimeoutSec 2
        return ($health.status -eq "UP")
    } catch { return $false }
}

function Wait-Until([scriptblock]$Condition, [int]$TimeoutSec, [string]$What) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 300
    }
    throw "Timed out after $TimeoutSec seconds waiting for: $What"
}

function Get-LedgerLines {
    if (-not (Test-Path $ledger)) { return @() }
    try { return @(Get-Content -LiteralPath $ledger -ErrorAction Stop) } catch { return @() }
}

function Count-Ledger($pattern) {
    return @(Get-LedgerLines | Where-Object { $_ -like $pattern }).Count
}

function Start-Background($file, $argumentLine, $name) {
    $options = @{
        FilePath               = $file
        ArgumentList           = $argumentLine
        RedirectStandardOutput = (Join-Path $logs "$name.out.log")
        RedirectStandardError  = (Join-Path $logs "$name.err.log")
        PassThru               = $true
    }
    if ($onWindows) { $options["WindowStyle"] = "Hidden" }
    $process = Start-Process @options
    [void]$started.Add($process)
    return $process
}

function Show-Tail($name, $count = 8) {
    $path = Join-Path $logs "$name.out.log"
    if (Test-Path $path) {
        Get-Content -LiteralPath $path -Tail $count | ForEach-Object { Say "   | $_" "DarkGray" }
    }
}

$failed = $false
$serverProcess = $null
try {
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    if (Test-Path $ledger) { Remove-Item -LiteralPath $ledger -Force }

    Step "Checking the setup"
    $java = Find-Java
    Say "Java: $java"
    if (-not (Test-Port "localhost" 5432)) {
        throw "PostgreSQL is not reachable on localhost:5432. Start it with:  docker compose up -d"
    }
    Say "PostgreSQL is reachable."

    if (-not $SkipBuild) {
        Step "Building the server and the demo worker (first time takes a minute)"
        $gradle = if ($onWindows) { ".\gradlew.bat" } else { "./gradlew" }
        & $gradle ":revenant-server:bootJar" ":revenant-demo:installDist" "--console=plain" "-q"
        if ($LASTEXITCODE -ne 0) { throw "The Gradle build failed. Run .\gradlew.bat build to see why." }
    }
    $jar = Get-ChildItem -Path (Join-Path $root "revenant-server\build\libs") -Filter "*.jar" |
        Where-Object { $_.Name -notlike "*-plain.jar" } | Select-Object -First 1
    $lib = Join-Path $root "revenant-demo\build\install\revenant-demo\lib"
    if (-not $jar) { throw "No server jar found. Run the script without -SkipBuild." }
    if (-not (Test-Path $lib)) { throw "No demo worker found. Run the script without -SkipBuild." }

    Step "Starting the server"
    if (Test-Health) {
        Say "A server is already running at $ServerUrl, using it."
    } else {
        $port = ([Uri]$ServerUrl).Port
        $serverProcess = Start-Background $java "-jar `"$($jar.FullName)`" --server.port=$port" "server"
        Wait-Until { Test-Health } 120 "the server to report UP (see demo-logs\server.out.log)"
        Say "Server is up (process $($serverProcess.Id))." "Green"
    }

    Step "Starting worker A"
    $workerArgs = "-cp `"$lib$([IO.Path]::DirectorySeparatorChar)*`" io.github.amitreddy14.revenant.demo.DemoWorker $ServerUrl `"$ledger`""
    $workerA = Start-Background $java $workerArgs "workerA"
    Wait-Until { (Test-Path (Join-Path $logs "workerA.out.log")) -and
                 (@(Get-Content -LiteralPath (Join-Path $logs "workerA.out.log") -ErrorAction SilentlyContinue) -match "is running").Count -gt 0 } 30 "worker A to start"
    Say "Worker A is running (process $($workerA.Id))." "Green"

    Step "Starting an order workflow: charge -> reserve stock -> ship (slow) -> send receipt"
    $orderId = "A-" + (Get-Date -Format "yyyyMMddHHmmss")
    $body = '{"orderId":"' + $orderId + '","amount":4999,"item":"book"}'
    $response = Invoke-RestMethod -Method Post -Uri "$ServerUrl/api/v1/workflows?type=OrderFulfilment" -Body $body -ContentType "application/json"
    $workflowId = $response.workflowId
    Say "Workflow $workflowId started for order $orderId."

    Wait-Until { (Count-Ledger "*| ship STARTED order=$orderId") -ge 1 } 90 "worker A to start shipping"
    Start-Sleep -Seconds 2
    Say "Worker A is in the middle of shipping:" "Yellow"
    Show-Tail "workerA" 6

    Step "Killing worker A without warning (the same as kill -9)"
    Stop-Process -Id $workerA.Id -Force
    [void]$workerA.WaitForExit(5000)
    Say "Worker A is dead: $($workerA.HasExited)" "Red"

    $workflow = Invoke-RestMethod -Uri "$ServerUrl/api/v1/workflows/$workflowId"
    Say "The server still knows the workflow. Status: $($workflow.status). History so far:"
    $workflow.history | ForEach-Object { Say ("   {0,2}  {1,-18} {2}" -f $_.seq, $_.type, $_.name) }
    Say "Nobody reported the shipping step. Its lease (10 s) must run out first."

    Step "Starting worker B"
    $killedAt = Get-Date
    $workerB = Start-Background $java $workerArgs "workerB"
    Say "Worker B is running (process $($workerB.Id)). It knows nothing about worker A."

    Wait-Until { (Invoke-RestMethod -Uri "$ServerUrl/api/v1/workflows/$workflowId").status -ne "RUNNING" } 120 "the workflow to finish"
    $seconds = [int]((Get-Date) - $killedAt).TotalSeconds
    $workflow = Invoke-RestMethod -Uri "$ServerUrl/api/v1/workflows/$workflowId"
    Say "The workflow finished $seconds seconds after worker B started. Status: $($workflow.status)" "Green"
    Show-Tail "workerB" 8

    Step "What each step really did (the ledger file)"
    Get-LedgerLines | ForEach-Object { Say "   $_" }

    Step "Checking the result"
    $completedSteps = @($workflow.history | Where-Object { $_.type -eq "ActivityCompleted" }).Count
    $checks = @(
        @{ Name = "workflow status";                       Expected = "COMPLETED"; Actual = "$($workflow.status)" },
        @{ Name = "charge ran";                            Expected = 1;           Actual = (Count-Ledger "*| charge order=$orderId *") },
        @{ Name = "reserve stock ran";                     Expected = 1;           Actual = (Count-Ledger "*| reserve order=$orderId *") },
        @{ Name = "ship started (killed once, then again)"; Expected = 2;          Actual = (Count-Ledger "*| ship STARTED order=$orderId") },
        @{ Name = "ship finished";                         Expected = 1;           Actual = (Count-Ledger "*| ship DONE order=$orderId") },
        @{ Name = "receipt sent";                          Expected = 1;           Actual = (Count-Ledger "*| receipt tracking=TRK-$orderId") },
        @{ Name = "steps recorded as completed";           Expected = 4;           Actual = $completedSteps }
    )
    foreach ($check in $checks) {
        $good = ("$($check.Actual)" -eq "$($check.Expected)")
        if (-not $good) { $failed = $true }
        $label = if ($good) { "PASS" } else { "FAIL" }
        $color = if ($good) { "Green" } else { "Red" }
        Say ("   {0}  {1,-40} expected {2}, got {3}" -f $label, $check.Name, $check.Expected, $check.Actual) $color
    }
    Write-Host ""
    if ($failed) {
        Say "RESULT: FAIL - see the lines above and the logs in demo-logs\" "Red"
    } else {
        Say "RESULT: PASS - the worker died mid-shipping, and the charge was not repeated." "Green"
    }
} catch {
    $failed = $true
    Say ""
    Say "The demo stopped: $($_.Exception.Message)" "Red"
    Say "Logs are in $logs" "Yellow"
} finally {
    foreach ($process in $started) {
        try { if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force } } catch { }
    }
}
if ($failed) { exit 1 }
