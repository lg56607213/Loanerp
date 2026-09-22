# ============================================================
#  서버별 설정 — 이 파일을 server-env.ps1 로 복사해 값을 채운다.
#  server-env.ps1 은 .gitignore 처리되어 커밋되지 않는다.
#  (DB 비밀번호가 들어가므로 절대 커밋하지 말 것)
# ============================================================

# ── 앱 환경변수 ─────────────────────────────────────────────
$Env:DB_URL_AUTH  = "jdbc:mysql://localhost:3306/loan_auth?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
$Env:DB_USERNAME  = "root"
$Env:DB_PASSWORD  = "여기에_서버_MySQL_비밀번호"

$Env:MAIL_USERNAME = "lg56607213@gmail.com"
$Env:MAIL_PASSWORD = "구글_앱비밀번호_또는_더미"

# 운영은 https(Cloudflare Tunnel) 뒤에 있으므로 true 를 권장한다.
# true 로 두면 http://<사설IP>:8080 직접 접속은 로그인이 유지되지 않는다.
$Env:COOKIE_SECURE = "true"
$Env:ERP_BASE_URL  = "https://erp.planbloan.co.kr"

# CODEF 계좌연동을 쓰지 않으면 비워둔다.
$Env:CODEF_CLIENT_ID     = ""
$Env:CODEF_CLIENT_SECRET = ""
$Env:CODEF_PUBLIC_KEY    = ""

# ── 배포 스크립트가 쓰는 경로 ───────────────────────────────
# 이 서버의 실제 설치 경로로 바꿀 것. javac -version 이 17 로 나와야 한다.
$JavaHome  = "C:\Program Files\Java\jdk-17.0.19"
$MysqlBin  = "C:\Program Files\MySQL\MySQL Server 8.0\bin"

$AppPort   = 8080
$HealthUrl = "http://localhost:8080/login.html"
$BackupDir = "C:\loan-erp-backup\predeploy"
