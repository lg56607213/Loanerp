# 배포 안내서 — Cloudflare Tunnel (B안)

> 대상 도메인: `erp.planbloan.co.kr`
> 방식: 이 PC에서 ERP를 띄우고 Cloudflare Tunnel로 노출한다. 서버 비용 0원.
> 공인 IP·포트 개방 불필요, HTTPS는 Cloudflare가 자동 처리.

---

## 0. 현재 상태

| 항목 | 값 |
|---|---|
| 도메인 등록기관 | 가비아 |
| 네임서버 | Cloudflare (`lola.ns.cloudflare.com`, `etienne.ns.cloudflare.com`) |
| DNS 관리 | **Cloudflare에서** (가비아는 건드릴 것 없음) |
| ERP 실행 포트 | `8081` (8080은 Marketing-API가 사용 중) |
| cloudflared | `C:\Users\home\cloudflared\cloudflared.exe` (설치 완료) |
| 설정 템플릿 | `C:\Users\home\cloudflared\config.yml` (UUID만 채우면 됨) |

---

## 1. ERP 실행

```bat
cd "C:\Users\home\Desktop\동천\제이디엔드\ERp솔루션\1차 결과물\loan-erp"
mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.port=8081
```

로컬 확인: <http://localhost:8081/login.html>

---

## 2. 터널 만들기 (최초 1회)

### 2-1. Cloudflare 로그인

```bat
C:\Users\home\cloudflared\cloudflared.exe tunnel login
```

브라우저가 열린다. Cloudflare 계정으로 로그인하고 **planbloan.co.kr 영역을 선택**하면
`C:\Users\home\.cloudflared\cert.pem` 이 생성된다.

### 2-2. 터널 생성

```bat
C:\Users\home\cloudflared\cloudflared.exe tunnel create planbloan-erp
```

출력에 나오는 **UUID**와 생성된 **json 파일 경로**를 기록해 둔다.

### 2-3. 설정 파일 채우기

`C:\Users\home\cloudflared\config.yml` 을 열어 `<TUNNEL_UUID>` 두 군데를 위에서 받은
UUID로 바꾼다.

### 2-4. DNS 레코드 생성

```bat
C:\Users\home\cloudflared\cloudflared.exe tunnel route dns planbloan-erp erp.planbloan.co.kr
```

Cloudflare DNS에 CNAME이 자동으로 추가된다. **가비아에서는 아무것도 안 해도 된다.**

---

## 3. 터널 실행

### 임시 실행 (터미널을 닫으면 종료)

```bat
C:\Users\home\cloudflared\cloudflared.exe tunnel --config C:\Users\home\cloudflared\config.yml run
```

### 상시 실행 (Windows 서비스, 관리자 권한 필요)

```bat
C:\Users\home\cloudflared\cloudflared.exe --config C:\Users\home\cloudflared\config.yml service install
```

접속 확인: <https://erp.planbloan.co.kr>

---

## 4. HTTPS 붙일 때 반드시 같이 할 것

Cloudflare 뒤에서는 세션 쿠키가 `Secure` 여야 로그인이 유지된다.
ERP를 띄울 때 환경변수를 준다.

```bat
set COOKIE_SECURE=true
mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.port=8081
```

`server.forward-headers-strategy: framework` 는 이미 `application.yml` 에 넣어 두었다.
이게 있어야 Cloudflare가 보낸 `X-Forwarded-Proto: https` 를 인식한다.

---

## 5. 접근 제한 (권장 — 대부업 특성상 중요)

이 ERP는 **고객 주민등록번호, 채권 내역, 법적절차 기록**을 다룬다.
인터넷에 그냥 열어두지 말고 Cloudflare Access로 관문을 하나 더 두는 것을 권한다.

Cloudflare 대시보드 → **Zero Trust → Access → Applications → Add an application**

- Type: Self-hosted
- Application domain: `erp.planbloan.co.kr`
- Policy: Allow → Emails → 접속을 허용할 직원 이메일 지정

무료 플랜에서 50명까지 쓸 수 있다. ERP 로그인 앞에 이메일 인증이 한 단계 더 붙는다.

---

## 6. 나중에 AWS로 옮길 때

코드는 그대로 두고 아래만 바꾸면 된다.

1. AWS Lightsail 인스턴스 생성 (서울 리전 `ap-northeast-2`, 4GB 권장)
2. 서버에 JDK 17 + MySQL 8 설치, `loan_erp` 생성
3. 이 저장소를 클론하고 환경변수로 접속정보 주입
   (`DB_URL_AUTH`, `DB_USERNAME`, `DB_PASSWORD`, `COOKIE_SECURE=true`)
4. Cloudflare DNS에서 `erp.planbloan.co.kr` 을 CNAME(터널) → A(서버 IP)로 변경
5. 터널 중지

DB는 `mysqldump` 로 옮기면 된다.

```bat
mysqldump -u root -p --databases loan_erp loan_company_planb > loan_backup.sql
```

---

## 7. 주의 — 아직 남은 보안 조치

### CODEF 시크릿 재발급 (미완료)

`application.yml` 에서 값은 제거했지만, **이미 푸시된 커밋 `0fb8417` 에 평문으로 남아
있고 저장소가 공개**다. 코드 수정만으로는 노출이 해소되지 않는다.

- CODEF 콘솔에서 client-secret 재발급
- 새 값은 `application-local.yml`(gitignore) 또는 환경변수로만 주입
- 저장소를 비공개로 전환하는 것도 함께 검토

### MySQL

3306/33060 인바운드는 Windows 방화벽에서 차단해 두었다(로컬 접속은 영향 없음).
AWS로 옮길 때도 보안그룹에서 3306을 절대 열지 말 것.
