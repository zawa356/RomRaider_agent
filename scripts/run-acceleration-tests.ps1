#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Jdk17 = (Join-Path $env:ProgramFiles 'Zulu\zulu-17'),
    [string]$Java8x86 = (Join-Path ${env:ProgramFiles(x86)} 'Zulu\zulu-8'),
    [string]$BashPath = (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
    [string]$Python = 'python',
    [switch]$Postgres,
    [switch]$RealServerHttp,
    [string]$ServerPath
)
$ErrorActionPreference = 'Stop'
if ($RealServerHttp -and (-not $Postgres -or -not $ServerPath)) { throw '-RealServerHttp requires -Postgres and an actual -ServerPath' }
if (-not $IsWindows) { throw 'Use the Bash test entry points on Linux; this batch is for the Windows development VM.' }
foreach ($exe in @((Join-Path $Jdk17 'bin\javac.exe'), (Join-Path $Java8x86 'bin\java.exe'), $BashPath)) {
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw "Required test tool missing: $exe" }
}
$repoRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repoRoot 'build'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$logPath = Join-Path $logDirectory ('acceleration-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$priorEnvironment = @{}
foreach ($key in @('JAVA_HOME','PATH','ROMLOGGER_EXPECT_DATA_MODEL','ROMLOGGER_RLC2_PREVIEW_ENABLE','ROMLOGGER_SERVER_PATH','ROMLOGGER_REQUIRE_SERVER_CONTRACT','ROMLOGGER_RLC2_HTTP_ENABLE')) {
    $priorEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
function Invoke-TestCommand {
    param([string]$Label, [string]$Executable, [string[]]$CommandArguments)
    "RUN $Label" | Tee-Object -FilePath $logPath -Append
    & $Executable @CommandArguments 2>&1 | Tee-Object -FilePath $logPath -Append
    if ($LASTEXITCODE -ne 0) { throw "Test failed: $Label (exit $LASTEXITCODE); see build log" }
}
Push-Location $repoRoot
try {
    $env:JAVA_HOME = $Jdk17
    $env:PATH = (Join-Path $Jdk17 'bin') + ';' + $priorEnvironment['PATH']
    $env:ROMLOGGER_EXPECT_DATA_MODEL = '64'
    Invoke-TestCommand 'JDK17 x64 version' 'java' @('-version')
    foreach ($script in @('test-ingress-journal-prototype.sh','test-ingress-sqlite-receipts.sh','test-windows-native-identity.sh','test-watermark-ledger.sh','test-ingress-diagnostics.sh','test-rlc2-preview.sh','test-phase2-lanes.sh','test-phase3-deployment.sh','test-phase4.sh')) {
        Invoke-TestCommand "Windows x64 $script" $BashPath @("scripts/$script")
    }
    $env:JAVA_HOME = $Java8x86
    $env:PATH = (Join-Path $Java8x86 'bin') + ';' + $priorEnvironment['PATH']
    $env:ROMLOGGER_EXPECT_DATA_MODEL = '32'
    Invoke-TestCommand 'Zulu8 x86 version' 'java' @('-version')
    foreach ($script in @('test-windows-native-identity.sh','test-watermark-ledger.sh','test-ingress-diagnostics.sh','test-rlc2-preview.sh','test-phase2-lanes.sh','test-phase3-deployment.sh','test-phase4.sh')) {
        Invoke-TestCommand "Zulu8 x86 $script" $BashPath @("scripts/$script",'--run-only')
    }
    foreach ($class in @('JournalIdentityComparisonTest','SplitLockJournalPrototypeTest','DurableWatermarkPrototypeTest')) {
        Invoke-TestCommand "Zulu8 x86 $class" 'java' @('-cp','build/ingress-journal-prototype',"net.neristal.rrbridge.prototype.ingress.$class")
    }
    Invoke-TestCommand 'PostgreSQL destination safety guards' $Python @('scripts/test-rlc2-preview-postgres-guard.py')
    Invoke-TestCommand 'real HTTP destination and opt-in guards' $Python @('scripts/test-rlc2-real-server-http-guard.py')
    if ($Postgres) {
        if (-not $env:DATABASE_URL) { throw 'Supply the disposable loopback DATABASE_URL through a protected process environment; it is never printed.' }
        $env:ROMLOGGER_RLC2_PREVIEW_ENABLE = '1'
        if ($ServerPath) { $env:ROMLOGGER_SERVER_PATH = $ServerPath; $env:ROMLOGGER_REQUIRE_SERVER_CONTRACT = '1' }
        Invoke-TestCommand 'isolated PostgreSQL preview and optional actual Server models' $Python @('scripts/test-rlc2-preview-postgres.py')
        if ($RealServerHttp) {
            $env:ROMLOGGER_RLC2_HTTP_ENABLE = '1'
            Invoke-TestCommand 'actual loopback RLC2 Server HTTP and PostgreSQL' $Python @('scripts/test-rlc2-real-server-http.py')
        } else { 'NOT RUN real RLC2 Server HTTP (use -RealServerHttp)' | Tee-Object -FilePath $logPath -Append }
    } else { 'NOT RUN PostgreSQL preview (use -Postgres with a protected test DSN)' | Tee-Object -FilePath $logPath -Append }
    $trackedBuild = & git ls-files -- build dist 'lib/*.jar' '*.class' '*.pyc'
    if ($LASTEXITCODE -ne 0 -or $trackedBuild) { throw 'Generated build artifacts are tracked; review Git before committing.' }
    'PASS acceleration batch; physical power loss/real ENOSPC/ECU NOT RUN; Windows directory durability UNKNOWN; new path ACK forbidden' | Tee-Object -FilePath $logPath -Append
    "Log: $logPath"
} finally {
    Pop-Location
    foreach ($key in $priorEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $priorEnvironment[$key], 'Process') }
}
