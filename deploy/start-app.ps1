# ============================================================
#  ERP 앱 기동 — 작업 스케줄러(LoanERP)가 부팅 시 실행하고,
#  배포 스크립트도 재시작할 때 이 작업을 호출한다.
#
#  설정 출처를 server-env.ps1 하나로 통일한다. 예전 run-server.bat 처럼
#  설정이 두 군데로 갈라지면 재부팅 후 배포본과 다른 값으로 뜬다.
#
#  ※ UTF-8 BOM 으로 저장할 것 (PowerShell 5.1 한글 주석).
# ============================================================
$ErrorActionPreference = "Stop"

$ScriptDir = $PSScriptRoot
$RepoDir   = Split-Path -Parent $ScriptDir
$EnvFile   = Join-Path $ScriptDir "server-env.ps1"
$LogDir    = Join-Path $ScriptDir "logs"
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

if (-not (Test-Path $EnvFile)) { throw "$EnvFile 이 없습니다." }
. $EnvFile

if (-not $AppPort) { $AppPort = 8080 }
$CurrentDir = Join-Path $RepoDir "current"
$CurrentJar = Join-Path $CurrentDir "loan-erp.jar"

# 이미 떠 있으면 아무것도 하지 않는다.
# 배포가 앱을 교체하는 도중에 이 작업이 겹쳐 실행돼도 두 번 뜨지 않게 한다.
if (Get-NetTCPConnection -LocalPort $AppPort -State Listen -ErrorAction SilentlyContinue) {
  Add-Content -Path (Join-Path $LogDir "start-app.log") -Encoding utf8 `
    -Value ("[{0}] 이미 기동되어 있어 건너뜀" -f (Get-Date -Format "MM-dd HH:mm:ss"))
  exit 0
}

# current\ 사본이 없으면 마지막 빌드 산출물에서 만든다 (최초 1회 등).
if (-not (Test-Path $CurrentJar)) {
  $built = Get-ChildItem (Join-Path $RepoDir "target\*.jar") -Exclude "*sources*","*javadoc*" -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $built) { throw "실행할 jar 가 없습니다. 먼저 빌드하세요." }
  New-Item -ItemType Directory -Path $CurrentDir -Force | Out-Null
  Copy-Item $built.FullName $CurrentJar -Force
}

if ($JavaHome) { $Env:JAVA_HOME = $JavaHome }
$java = if ($JavaHome) { Join-Path $JavaHome "bin\java.exe" } else { "java" }
$opts = if ($JvmOpts) { $JvmOpts } else { @() }

Add-Content -Path (Join-Path $LogDir "start-app.log") -Encoding utf8 `
  -Value ("[{0}] 기동: {1}" -f (Get-Date -Format "MM-dd HH:mm:ss"), $CurrentJar)

# 포그라운드로 띄운다. 작업이 Running 으로 유지되어 배포가 이 작업을 통해 제어할 수 있다.
& $java @opts "-jar" $CurrentJar "--server.port=$AppPort"