# 서버용 PC 구축 안내서

> 상시 가동할 PC 한 대를 ERP 서버로 만들고, 외부(집·카페)에서 접속할 수 있게 하는 절차.
> 다른 PC에서 실제로 한 번 끝까지 해보고, **그때 막혔던 지점만** 추린 문서다.
> `local/README.md`(로컬 구축)와 `deploy-cloudflare-tunnel.md`(외부 노출)를 실행 순서대로 엮고,
> 두 문서에 없거나 틀린 부분을 여기서 보완한다.

구성은 이렇게 된다.

```
[서버 PC]  MySQL + ERP 앱 + Cloudflare Tunnel    ← 항상 켜둠
     ↕ 인터넷 (https)
[노트북] [집 PC] [휴대폰]                          ← 브라우저만. 설치할 것 없음
```

---

## 0. 준비물

| | 비고 |
|---|---|
| JDK 17 | 설치 경로는 PC마다 다르다. §1-1 참고 |
| MySQL 8.0 | root 비밀번호를 강하게 잡을 것 (§5) |
| 이 저장소 | `git clone` |

---

## 1. 먼저 알아야 할 함정 7가지

구축 자체는 `local/README.md`를 따라가면 되지만, 아래를 모르면 같은 자리에서 막힌다.
**순서대로 읽고 시작할 것.**

### 1-1. JDK 경로는 문서를 믿지 말 것

`local/run-local.bat.example`에는 `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`이 적혀 있지만
그 PC에만 있던 경로다. 실제 설치 위치를 직접 확인한다.

```powershell
Get-ChildItem "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft" -ErrorAction SilentlyContinue
```

`javac -version`이 17로 나오는지까지 확인한다. JRE만 있으면 빌드가 안 된다.

### 1-2. 배치 파일은 반드시 CRLF

`run-local.bat`을 LF 줄바꿈으로 저장하면 cmd가 **한 줄도 해석하지 못한다.**
`'http:'은(는) 내부 또는 외부 명령...`처럼 엉뚱한 오류가 줄줄이 난다.

bash 계열 도구로 만들었다면 변환한다.

```bash
sed 's/$/\r/' tmp.txt > run-local.bat
file run-local.bat        # "with CRLF line terminators" 확인
```

### 1-3. `call mvnw.cmd` 가 아니라 `call ".\mvnw.cmd"`

환경변수 `NoDefaultCurrentDirectoryInExePath=1`이 설정된 PC에서는 cmd가 **현재 폴더에서
배치 파일을 찾지 않는다.** 파일이 눈앞에 있어도 "내부 또는 외부 명령이 아닙니다"가 뜬다.

```bat
REM 안 됨
call mvnw.cmd spring-boot:run
REM 됨
call ".\mvnw.cmd" spring-boot:run
```

확인: `cmd /c "set NoDefaultCurrentDirectoryInExePath"`

### 1-4. mysql 클라이언트에 `--default-character-set=utf8mb4`

한글 Windows의 mysql 클라이언트는 기본이 euckr이라, 빼면 SQL 안의 한글에서
`ERROR 3854 ... Cannot convert string from euckr to utf8mb3`가 난다.

```bat
mysql.exe -u root -p --default-character-set=utf8mb4 < local\1-create-databases.sql
```

### 1-5. 템플릿 DB 시딩을 건너뛰면 회사 생성이 실패한다

`local/README.md` 2단계다. `ddl-auto`는 `loan_erp`(템플릿)에 돌지 않으므로,
한 번은 `DB_URL_AUTH`를 `loan_erp`로 돌려 앱을 띄워야 한다.

**검증 없이 넘어가지 말 것.** 아래 두 숫자를 확인한다.

```sql
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='loan_erp';  -- 39
SELECT COUNT(*) FROM loan_erp.financial_statement_accounts;                    -- 80
```

회사를 만든 뒤에도 똑같이 확인한다. **계정과목이 0건이면 시딩이 안 된 것이다.**

```sql
SELECT COUNT(*) FROM loan_company_<회사>.financial_statement_accounts;         -- 80
```

### 1-6. cloudflared 서비스는 실행 인자가 비어 있다

`cloudflared service install`이 서비스를 등록하지만 **`tunnel run` 인자를 넣지 않는다.**
그 상태로 시작하면 즉시 종료되고, 이벤트 로그에 "예기치 않게 N번 종료"만 쌓인다.
사이트는 502가 뜬다.

실행 명령을 직접 지정한다(관리자 권한).

```bat
sc.exe config Cloudflared binPath= "\"C:\경로\cloudflared.exe\" --config \"C:\Windows\System32\config\systemprofile\.cloudflared\config.yml\" --no-autoupdate tunnel run"
```

> 서비스가 멈추지 않을 때는 `Stop-Service`가 `StopPending`에서 걸린다.
> `Get-Process cloudflared`로 PID를 찾아 `Stop-Process -Force`로 끊는다.

### 1-7. 설정 파일은 PowerShell로 옮기지 말 것

서비스(LocalSystem)는 `C:\Windows\System32\config\systemprofile\.cloudflared\config.yml`을 읽는다.
여기로 옮길 때 PowerShell 5.1의 `Get-Content`/`Set-Content`를 쓰면

- `-Encoding utf8`이 **BOM**을 붙이고
- 한글 주석이 시스템 코드페이지로 **깨진다**

둘 다 `yaml: control characters are not allowed`로 이어진다. **바이트 그대로 복사한다.**

```powershell
Copy-Item "C:\경로\config.yml" "$env:SystemRoot\System32\config\systemprofile\.cloudflared\config.yml" -Force
```

복사 후 반드시 검증한다.

```bat
cloudflared.exe --config "C:\Windows\System32\config\systemprofile\.cloudflared\config.yml" tunnel ingress validate
```

---

## 2. 구축 순서

1. **로컬 구축** — `local/README.md` 1~5단계. 위 §1-1 ~ §1-5를 반영해서 진행한다.
2. **동작 확인** — `http://localhost:8080/login.html` 로그인, 고객 1건 등록 후 DB에서 직접 조회, 삭제.
3. **보안 점검** — §5. **외부에 열기 전에 한다.**
4. **터널 구축** — `deploy-cloudflare-tunnel.md`. 위 §1-6, §1-7을 반영한다.
5. **자동 시작** — §4. 이걸 안 하면 서버가 아니다.

---

## 3. 터널을 다른 PC에서 옮겨올 때

터널은 **한 번에 한 대에서만** 돌아야 한다. 기존 PC에서 돌고 있다면:

1. 새 PC에서 `cloudflared tunnel login` → `cloudflared tunnel create <새이름>`
2. `cloudflared tunnel route dns <새이름> erp.planbloan.co.kr`
   기존 CNAME을 덮어쓴다. 가비아에서는 아무것도 안 해도 된다.
3. **기존 PC에서 서비스를 제거한다.** 안 하면 두 터널이 같은 이름을 두고 경합한다.

```powershell
Stop-Service Cloudflared -Force
& "C:\경로\cloudflared.exe" service uninstall
```

`config.yml`의 `service:` 포트가 실제 앱 포트와 같은지 확인한다.
저장소 템플릿은 `8081`로 되어 있는데 `local/README.md` 절차로 띄우면 **8080**이다.

---

## 4. 자동 시작 — 안 하면 서버가 아니다

세 가지가 모두 부팅 후 스스로 떠야 한다.

| | 기본값 | 확인 |
|---|---|---|
| MySQL80 | Automatic (설치 시 자동) | `Get-Service MySQL80` |
| Cloudflared | Automatic (`service install` 시) | `Get-Service Cloudflared` |
| **ERP 앱** | **없음 — 직접 걸어야 함** | |

ERP 앱은 `mvnw spring-boot:run`으로 띄우면 그 콘솔 창(또는 그걸 띄운 프로세스)에 묶인다.
터미널을 닫거나 도구 세션이 끝나면 앱만 죽고, MySQL·터널은 살아 있어서
**사이트에 502만 뜬다.** 원인을 찾기 어렵다.

작업 스케줄러에 등록하거나(트리거: 시스템 시작 시, 사용자 로그온과 무관하게 실행),
`jar`로 빌드해 서비스로 감싼다.

구축이 끝나면 **실제로 재부팅해서** 세 가지가 다 올라오는지 확인한다.

---

## 5. 외부에 열기 전 보안 점검

### 5-1. MySQL 3306 — 문서를 믿지 말고 직접 확인할 것

`deploy-cloudflare-tunnel.md` §7과 `local/README.md`에 "방화벽에서 차단해 두었다"고 적혀 있었지만,
실제로 확인해보니 **차단되어 있지 않았다.** 그 PC에는 이런 규칙이 살아 있었다.

```
Name: Port 3306 / Enabled: True / Action: Allow / Profile: Any / RemoteAddress: Any
```

MySQL은 `0.0.0.0:3306`에 바인딩되므로, 이 상태면 **같은 네트워크의 아무 PC나 root로 DB에 직접 붙을 수 있다.**
앱 인증을 아무리 막아도 채무자 실데이터가 든 `loan_company_*`가 통째로 노출된다.

```powershell
Get-NetFirewallRule -Direction Inbound -Enabled True -Action Allow |
  Where-Object { ($_ | Get-NetFirewallPortFilter).LocalPort -eq 3306 }
```

규칙이 나오면 비활성화한다. 앱은 localhost로 붙으므로 영향이 없다.

> Cloudflare Tunnel 자체는 `config.yml`의 ingress 경로만 통과시키므로 3306을 노출하지 않는다.
> 포트포워딩과 다른 점이다. 그래도 LAN 안에서의 노출은 남으므로 막는 게 맞다.

### 5-2. 계정 비밀번호

`local/3-create-admin.sql`의 예시 값과 구축 중 임시로 정한 값은 **반드시 바꾼다.**
외부에 열면 로그인 화면이 전 세계에서 보인다. MySQL root 비밀번호도 마찬가지다.

### 5-3. `COOKIE_SECURE=true`

HTTPS로 열 때 켠다. `same-site: lax`라 끄고도 로그인은 되지만, 켜야 세션 쿠키가
평문 HTTP로 새는 경로가 막힌다.

**트레이드오프:** 켜면 `http://<사설IP>:8080` 사내 IP 접속은 로그인이 깨진다.
터널은 사무실 안에서도 동작하므로, 안팎 모두 `https://` 도메인만 쓰면 손해가 없다.

### 5-4. Cloudflare Access

`deploy-cloudflare-tunnel.md` §5. 주소를 아는 사람은 누구나 로그인 화면까지 도달하므로,
ERP 로그인 앞에 이메일 인증 관문을 하나 더 둔다. 무료 플랜 50명.
접속 기록이 남아 신용정보법상 접근통제·접속기록 요건에도 도움이 된다.

### 5-5. 구축 직후 확인할 것

```bash
# 1) 인증 없이 API  → 401 이어야 한다
curl -o /dev/null -w "%{http_code}\n" https://<도메인>/api/customers

# 2) 로그인 실패로 세션만 받아 다시 시도  → 여전히 401 이어야 한다
curl -c c.txt -X POST https://<도메인>/api/auth/login \
     -H "Content-Type: application/json" -d '{"companyLoginId":"x","companyPassword":"x"}'
curl -o /dev/null -w "%{http_code}\n" -b c.txt https://<도메인>/api/customers
```

2번이 **200이면 인증 우회가 살아 있는 것이다.** `TenantFilter`의 `/api/**` 분기가
`session == null`만 보고 있지 않은지 확인한다(로그인 실패에도 JSESSIONID가 발급되므로
빈 세션으로 통과된다). 현재는 수정되어 있다.

---

## 6. 알려진 버그

### `adminUpdate` — 회사 비밀번호를 바꾸면 그 회사 로그인이 깨진다

회사 계정 로그인(사용자 아이디/비밀번호를 비우는 방식)은 `login_users`와 `company_users`
**양쪽 비밀번호가 모두 일치해야** 통과한다. `AuthService.adminCreate`는 두 곳에 같은 해시를 넣지만,
`AuthService.adminUpdate`는 `login_users`만 갱신한다.

→ 관리자 화면에서 회사 비밀번호를 바꾸면 그 회사가 로그인하지 못한다.

고치기 전까지는 바꾼 뒤 직접 맞춰준다.

```sql
UPDATE company_users cu
  JOIN login_users lu ON lu.id = cu.company_id AND lu.login_id = cu.user_login_id
   SET cu.user_password = lu.login_password
 WHERE lu.login_id = '<회사아이디>';
```

---

## 7. 백업

실데이터를 넣는 순간부터 필수다. 채권 장부라 한 번 날리면 복구할 방법이 없다.

수집 대상은 `loan\_%` 패턴으로 잡으면 `loan_auth` + `loan_erp` + `loan_company_*`가 모두 들어온다.

```powershell
$dbs = & mysql.exe "-uroot" "-p$pw" -N -e "SHOW DATABASES LIKE 'loan\_%'"
& mysqldump.exe "-uroot" "-p$pw" --single-transaction --routines `
    --default-character-set=utf8mb4 --databases $dbs > backup.sql
```

작업 스케줄러로 매일 돌리고, **덤프 파일도 같이 보호한다.**
채무자 주민등록번호가 평문으로 들어 있다. 백업이 바탕화면에 그대로 있으면 의미가 없다.
