# ============================================================
#  자동 배포 감시 — 작업 스케줄러가 1분마다 실행한다.
#
#  깃허브 main 에 새 커밋이 있으면 deploy.ps1 을 부른다.
#  없으면 아무것도 하지 않고 즉시 끝난다(부하 없음).
#
#  무한 루프 대신 '짧게 실행되고 끝나는' 방식을 쓴다.
#  프로세스가 죽어도 스케줄러가 다음 분에 다시 띄우므로 스스로 복구된다.
# ============================================================
$ErrorActionPreference = "Stop"

# deploy.ps1 과 같은 이유로 네이티브 호출을 감싼다.
# PowerShell 5.1 에서 git 의 stderr 진행 출력이 ErrorRecord 가 되어 예외로 터지면,
# 이 스크립트는 catch 로 빠져 "감시 스크립트 오류"만 남기고 영영 배포하지 않는다.
function Invoke-Native {
  param([Parameter(Mandatory)][string]$File, [string[]]$Arguments = @())
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $lines = & $File @Arguments 2>&1 | ForEach-Object { "$_" }
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Lines = $lines }
  } finally { $ErrorActionPreference = $prev }
}

$RepoDir = Split-Path -Parent $PSScriptRoot
$LogDir  = Join-Path $PSScriptRoot "logs"
$Marker  = Join-Path $PSScriptRoot ".last-deployed"
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

$watchLog = Join-Path $LogDir "watch.log"
function W($m) {
  Add-Content -Path $watchLog -Value ("[{0}] {1}" -f (Get-Date -Format "MM-dd HH:mm:ss"), $m) -Encoding utf8
}

try {
  Set-Location $RepoDir
  Invoke-Native "git" @("fetch","origin","main") | Out-Null

  $local  = (& git rev-parse HEAD).Trim()
  $remote = (& git rev-parse origin/main).Trim()

  if ($local -eq $remote) { exit 0 }          # 변화 없음 — 조용히 종료

  # 같은 커밋으로 반복 실패하는 경우 1분마다 재시도하며 로그를 채우지 않도록 막는다.
  if ((Test-Path $Marker) -and (Get-Content $Marker -Raw).Trim() -eq $remote) {
    W "새 커밋 $($remote.Substring(0,7)) 은 직전에 실패했습니다. 수동 확인이 필요합니다."
    exit 0
  }

  W "새 커밋 감지: $($local.Substring(0,7)) -> $($remote.Substring(0,7)) — 배포 시작"
  Set-Content -Path $Marker -Value $remote -Encoding ascii

  $run = Invoke-Native "powershell" @("-NoProfile","-ExecutionPolicy","Bypass","-File",(Join-Path $PSScriptRoot "deploy.ps1"))
  $run.Lines | ForEach-Object { W "  $_" }

  if ($run.ExitCode -eq 0) {
    W "배포 성공 — $($remote.Substring(0,7))"
    Remove-Item $Marker -ErrorAction SilentlyContinue   # 성공했으니 표식을 지운다
  } else {
    W "배포 실패 — 이 커밋은 다시 시도하지 않습니다. deploy 로그를 확인하세요."
  }
}
catch {
  W "감시 스크립트 오류: $_"
}
