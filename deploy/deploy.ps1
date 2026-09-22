# ============================================================
#  LoanERP 운영 배포 — 서버 PC 에서 실행한다.
#
#  순서: DB 백업 -> git pull -> 빌드 -> 재시작 -> 헬스체크
#        헬스체크가 실패하면 직전 커밋으로 자동 롤백한다.
#
#  실행:
#     powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1
#     powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Force
#
#  -Force : 새 커밋이 없어도 다시 빌드하고 재시작한다.
# ============================================================
param(
  [switch]$Force,
  [int]   $HealthTimeoutSec = 150
)

$ErrorActionPreference = "Stop"

# ── 경로 ────────────────────────────────────────────────────
$RepoDir   = Split-Path -Parent $PSScriptRoot        # deploy 의 부모 = 저장소 루트
$LogDir    = Join-Path $PSScriptRoot "logs"
$EnvFile   = Join-Path $PSScriptRoot "server-env.ps1"
$stamp     = Get-Date -Format "yyyyMMdd_HHmmss"
$LogFile   = Join-Path $LogDir "deploy_$stamp.log"

# 운영 중인 앱은 target\ 이 아니라 current\ 의 사본에서 돌린다.
# 윈도우에서는 실행 중인 jar 가 잠겨, target\ 에서 바로 돌리면 다음 빌드의
# spring-boot:repackage 가 "Unable to rename ... .jar.original" 로 실패한다.
$CurrentDir = Join-Path $RepoDir "current"
$CurrentJar = Join-Path $CurrentDir "loan-erp.jar"

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

function Log($msg) {
  $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
  Write-Output $line
  Add-Content -Path $LogFile -Value $line -Encoding utf8
}

function Fail($msg) {
  Log "[실패] $msg"
  throw $msg
}

# ── 서버별 설정 (git 에 올리지 않는다) ──────────────────────
# server-env.ps1 이 아래 값들을 정의해야 한다:
#   $Env:DB_URL_AUTH, $Env:DB_USERNAME, $Env:DB_PASSWORD,
#   $Env:MAIL_USERNAME, $Env:MAIL_PASSWORD, $Env:COOKIE_SECURE, $Env:ERP_BASE_URL
#   $JavaHome, $MysqlBin, $AppPort, $HealthUrl, $BackupDir
if (-not (Test-Path $EnvFile)) {
  Fail "$EnvFile 이 없습니다. deploy/server-env.example.ps1 을 복사해 값을 채우세요."
}
. $EnvFile

if (-not $AppPort)   { $AppPort   = 8080 }
if (-not $HealthUrl) { $HealthUrl = "http://localhost:$AppPort/login.html" }

Log "=== 배포 시작 (저장소: $RepoDir) ==="

Set-Location $RepoDir

# ── 1. 새 커밋이 있는지 확인 ────────────────────────────────
$before = (& git rev-parse HEAD).Trim()
& git fetch origin main 2>&1 | Out-Null
$remote = (& git rev-parse origin/main).Trim()

Log "현재 $($before.Substring(0,7)) / 원격 $($remote.Substring(0,7))"

if ($before -eq $remote -and -not $Force) {
  Log "새 커밋이 없습니다. 아무것도 하지 않고 종료합니다."
  exit 0
}

# ── 2. DB 백업 (되돌릴 수 없는 작업이므로 먼저) ─────────────
if ($MysqlBin -and (Test-Path $MysqlBin)) {
  if (-not $BackupDir) { $BackupDir = Join-Path $PSScriptRoot "backups" }
  New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
  $dumpFile = Join-Path $BackupDir "predeploy_$stamp.sql"

  $Env:MYSQL_PWD = $Env:DB_PASSWORD   # -p 인자는 프로세스 목록에 노출되므로 환경변수로 준다
  $dbs = & "$MysqlBin\mysql.exe" "-u$($Env:DB_USERNAME)" -N `
           -e "SHOW DATABASES LIKE 'loan\_%'" 2>$null |
         Where-Object { $_ -and $_.Trim() -ne "" }

  if ($dbs) {
    $args = @("-u$($Env:DB_USERNAME)", "--single-transaction",
              "--routines", "--default-character-set=utf8mb4", "--databases") + $dbs
    & "$MysqlBin\mysqldump.exe" @args 2>$null | Out-File -FilePath $dumpFile -Encoding utf8
    $mb = [math]::Round((Get-Item $dumpFile).Length / 1MB, 2)
    Log "DB 백업 완료: $dumpFile ($mb MB) — 대상: $($dbs -join ', ')"
  } else {
    Log "[경고] 백업할 DB 를 찾지 못했습니다. 계속 진행합니다."
  }
} else {
  Log "[경고] MysqlBin 경로가 없어 DB 백업을 건너뜁니다."
}

# ── 3. 코드 받기 ────────────────────────────────────────────
$dirty = & git status --porcelain
if ($dirty) {
  Fail "서버 저장소에 커밋되지 않은 변경이 있습니다. 서버에서는 코드를 직접 고치지 마세요.`n$dirty"
}

& git pull --ff-only origin main 2>&1 | ForEach-Object { Log "  git: $_" }
if ($LASTEXITCODE -ne 0) { Fail "git pull 실패" }

$after = (& git rev-parse HEAD).Trim()
Log "코드 갱신: $($before.Substring(0,7)) -> $($after.Substring(0,7))"

# ── 4. 빌드 ─────────────────────────────────────────────────
if ($JavaHome) { $Env:JAVA_HOME = $JavaHome }

Log "빌드 시작 (테스트 제외)"
& ".\mvnw.cmd" -q -DskipTests package 2>&1 | ForEach-Object { Log "  mvn: $_" }
if ($LASTEXITCODE -ne 0) {
  Log "[실패] 빌드 실패 — 코드를 되돌립니다."
  & git reset --hard $before 2>&1 | Out-Null
  Fail "빌드 실패. 운영은 건드리지 않았습니다(앱이 계속 돌고 있음)."
}

$jar = Get-ChildItem "target\*.jar" -Exclude "*sources*","*javadoc*" |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { Fail "빌드 산출물(jar)을 찾지 못했습니다." }
Log "빌드 완료: $($jar.Name)"

# ── 5. 재시작 ───────────────────────────────────────────────
function Stop-App {
  # 앱을 작업 스케줄러가 소유하는 서버에서는 작업을 먼저 멈춘다.
  # 안 그러면 배포가 죽인 앱을 스케줄러가 곧바로 되살려 두 개가 포트를 놓고 경합한다.
  if ($AppTaskName) {
    Stop-ScheduledTask -TaskName $AppTaskName -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
  }
  $pids = (Get-NetTCPConnection -LocalPort $AppPort -State Listen -ErrorAction SilentlyContinue).OwningProcess |
          Sort-Object -Unique
  foreach ($p in $pids) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 3
}

function Start-App($jarPath) {
  # 작업 스케줄러가 소유자면 그쪽으로 띄운다(부팅 경로와 동일한 설정으로 뜨게 하려고).
  # 이때 jar 경로는 무시된다 — 작업은 Publish-Jar 가 갱신한 current\ 사본을 읽는다.
  if ($AppTaskName) {
    Start-ScheduledTask -TaskName $AppTaskName
    return
  }
  $java = if ($JavaHome) { Join-Path $JavaHome "bin\java.exe" } else { "java" }
  Start-Process -FilePath $java `
    -ArgumentList ((&{ if ($JvmOpts) { $JvmOpts } else { @() } }) + @("-jar", $jarPath, "--server.port=$AppPort")) `
    -WorkingDirectory $RepoDir `
    -RedirectStandardOutput (Join-Path $LogDir "app_$stamp.out.log") `
    -RedirectStandardError  (Join-Path $LogDir "app_$stamp.err.log") `
    -WindowStyle Hidden
}

# 빌드 산출물을 current\ 로 옮긴다. 앱은 반드시 이 사본에서만 돌린다.
function Publish-Jar($srcJar) {
  New-Item -ItemType Directory -Path $CurrentDir -Force | Out-Null
  Copy-Item $srcJar $CurrentJar -Force
  Log "  운영 jar 갱신: $CurrentJar"
}

function Test-Health {
  $deadline = (Get-Date).AddSeconds($HealthTimeoutSec)
  while ((Get-Date) -lt $deadline) {
    try {
      $r = Invoke-WebRequest -Uri $HealthUrl -TimeoutSec 5 -UseBasicParsing
      if ($r.StatusCode -eq 200) { return $true }
    } catch { }
    Start-Sleep -Seconds 3
  }
  return $false
}

Log "앱 재시작"
Stop-App
Publish-Jar $jar.FullName
Start-App $CurrentJar

if (Test-Health) {
  Log "헬스체크 통과 — 배포 완료 ($($after.Substring(0,7)))"
  exit 0
}

# ── 6. 롤백 ─────────────────────────────────────────────────
Log "[실패] 헬스체크 실패 — $($before.Substring(0,7)) 로 되돌립니다."
Stop-App
& git reset --hard $before 2>&1 | Out-Null
& ".\mvnw.cmd" -q -DskipTests package 2>&1 | Out-Null

$oldJar = Get-ChildItem "target\*.jar" -Exclude "*sources*","*javadoc*" |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
Publish-Jar $oldJar.FullName
Start-App $CurrentJar

if (Test-Health) {
  Fail "새 코드가 뜨지 않아 이전 버전으로 되돌렸습니다. 운영은 정상입니다. 로그: $LogFile"
} else {
  Fail "롤백 후에도 앱이 뜨지 않습니다. 즉시 확인이 필요합니다. 로그: $LogFile"
}
