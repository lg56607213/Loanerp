# 자동 배포 설정 — 서버 PC에서 한 번만

> **이 문서를 읽는 대상: 서버 PC(192.168.219.44)에서 작업하는 사람 또는 Claude Code.**
>
> 개발 PC에서 `git push` 하면 운영 서버가 스스로 받아 배포하도록 만든다.
> 급할 때는 개발 PC에서 직접 밀어 넣을 수도 있게 한다.
>
> 구축 자체는 `server-pc-setup.md` 를 참고한다. 이 문서는 **배포 자동화만** 다룬다.

---

## 무엇이 만들어지나

```
[개발 PC]  수정 → git push
                     ↓
              [GitHub main]
                     ↓  1분마다 확인
[서버 PC]  DB 백업 → git pull → 빌드 → 재시작 → 헬스체크
                     └ 실패하면 직전 커밋으로 자동 롤백
```

배포는 **되돌릴 수 있게** 되어 있다. 빌드가 깨지면 앱을 건드리지 않고,
새 코드가 뜨지 않으면 직전 커밋으로 되돌린 뒤 다시 띄운다.

---

## 1. 설정 파일 만들기

```powershell
cd <저장소>\deploy
Copy-Item server-env.example.ps1 server-env.ps1
notepad server-env.ps1
```

채워야 하는 값:

| 항목 | 설명 |
|---|---|
| `$Env:DB_PASSWORD` | 이 서버의 MySQL root 비밀번호 |
| `$JavaHome` | **이 PC의 실제 JDK 17 경로.** `javac -version` 이 17 이어야 한다 |
| `$MysqlBin` | mysql.exe / mysqldump.exe 가 있는 폴더 |
| `$Env:COOKIE_SECURE` | 운영은 https 뒤이므로 `true` 권장 |
| `$BackupDir` | 배포 직전 DB 덤프를 둘 곳 |

`server-env.ps1` 은 `.gitignore` 처리되어 있다. **절대 커밋하지 말 것** — 저장소가 공개다.

---

## 2. 먼저 손으로 한 번 돌려본다

자동화를 걸기 전에 반드시 수동으로 성공시켜 본다.

```powershell
powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Force
```

`-Force` 는 새 커밋이 없어도 빌드·재시작을 한다. 기대 결과:

```
[HH:mm:ss] DB 백업 완료: ... (N MB)
[HH:mm:ss] 빌드 완료: loan-erp-0.0.1-SNAPSHOT.jar
[HH:mm:ss] 앱 재시작
[HH:mm:ss] 헬스체크 통과 — 배포 완료 (xxxxxxx)
```

### 여기서 확인할 것

- **앱이 지금 어떻게 떠 있는지 먼저 파악한다.** `mvnw spring-boot:run` 으로 콘솔에 떠 있다면,
  이 스크립트는 그 프로세스를 죽이고 `java -jar` 로 다시 띄운다. 기동 방식이 바뀌므로
  기존 자동 시작(작업 스케줄러 등)이 있다면 **그것도 `java -jar` 로 맞춰야** 재부팅 후 충돌하지 않는다.
- 헬스체크가 실패하면 로그가 `deploy\logs\deploy_*.log` 와 `app_*.err.log` 에 남는다.

---

## 3. 1분마다 자동 배포 걸기

관리자 PowerShell:

```powershell
$repo = "<저장소 절대경로>"
$action  = New-ScheduledTaskAction -Execute "powershell.exe" `
           -Argument "-ExecutionPolicy Bypass -WindowStyle Hidden -File `"$repo\deploy\check-and-deploy.ps1`""
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
           -RepetitionInterval (New-TimeSpan -Minutes 1)
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -RunLevel Highest
Register-ScheduledTask -TaskName "LoanERP-AutoDeploy" `
  -Action $action -Trigger $trigger -Principal $principal -Force
```

확인:

```powershell
Get-ScheduledTask LoanERP-AutoDeploy | Select-Object TaskName, State
Get-Content deploy\logs\watch.log -Tail 20
```

새 커밋이 없으면 아무것도 하지 않고 즉시 끝나므로 부하는 없다.

> **같은 커밋으로 반복 실패하면 재시도하지 않는다.** `deploy\.last-deployed` 에 실패한
> 커밋을 적어두고 건너뛴다. 고친 뒤 새 커밋을 올리거나 그 파일을 지우면 다시 시도한다.

---

## 4. 개발 PC에서 직접 밀어 넣기 (선택)

급할 때 개발 PC에서 바로 배포하려면 이 서버에 SSH 를 열어야 한다.

```powershell
# OpenSSH 서버 설치·시작
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Start-Service sshd
Set-Service -Name sshd -StartupType Automatic
New-NetFirewallRule -DisplayName "OpenSSH (LAN only)" -Direction Inbound `
  -Protocol TCP -LocalPort 22 -Action Allow -Profile Private
```

개발 PC의 공개키를 등록한다 (아래 키는 개발 PC에서 만든 것):

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF0b2Aa8TKv7NSnRRUJUZVhyHa306DhIHyoL+F6YxvKz loanerp-deploy@devpc
```

관리자 계정이면 Windows 에서는 `administrators_authorized_keys` 를 쓴다:

```powershell
$k = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF0b2Aa8TKv7NSnRRUJUZVhyHa306DhIHyoL+F6YxvKz loanerp-deploy@devpc"
$f = "$Env:ProgramData\ssh\administrators_authorized_keys"
Add-Content -Path $f -Value $k -Encoding ascii
icacls $f /inheritance:r /grant "Administrators:F" /grant "SYSTEM:F"
```

> 22번은 **Private 프로필로만** 연다. 인터넷에 노출하지 않는다.
> Cloudflare Tunnel 은 `config.yml` 의 ingress 경로만 통과시키므로 SSH 는 터널로 새지 않는다.

설정이 끝나면 개발 PC에서 이렇게 배포할 수 있다:

```bash
ssh -i ~/.ssh/loanerp_deploy <계정>@192.168.219.44 \
  "powershell -ExecutionPolicy Bypass -File C:\경로\deploy\deploy.ps1"
```

---

## 5. 자동 배포를 켜기 전에 정하고 갈 것

자동 배포는 **개발 PC에서 밀어 넣은 코드가 곧바로 운영에 올라간다**는 뜻이다.
채무자 실데이터가 있는 시스템이므로 아래를 권한다.

- **재시작 중 1~2분은 접속이 끊긴다.** 업무 시간에 푸시하지 않거나,
  `check-and-deploy.ps1` 의 트리거를 야간으로 돌린다.
- **DB 스키마가 바뀌는 변경은 자동 배포에 맡기지 않는다.** `ddl-auto: update` 는
  컬럼 추가만 하고 삭제·타입 변경은 하지 않으므로, 코드와 운영 DB 가 조용히 어긋난다.
  그런 변경은 손으로 배포하고 결과를 확인한다.
- **배포 직전 DB 덤프가 `$BackupDir` 에 쌓인다.** 디스크가 차지 않게 주기적으로 정리한다.
  채무자 주민등록번호가 평문으로 들어 있으므로 덤프 파일도 보호 대상이다.

---

## 6. 문제가 생기면

| 증상 | 확인 |
|---|---|
| 배포가 안 됨 | `deploy\logs\watch.log` — 감시가 돌고 있는지 |
| 배포는 됐는데 사이트가 502 | 앱이 안 뜬 것. `deploy\logs\app_*.err.log` |
| 롤백까지 실패 | `deploy\logs\deploy_*.log` 를 보고 수동 복구. DB 는 `$BackupDir` 의 덤프로 되돌린다 |
| 같은 커밋이 계속 실패 | `deploy\.last-deployed` 를 지우면 다시 시도한다 |

수동 롤백:

```powershell
git reset --hard <직전 커밋>
powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Force
```
