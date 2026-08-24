# 로컬 시범운영 안내 (플랜비대부 실데이터 1개월)

목적: **계산이 실제로 맞는지** 확인하는 것. 도메인·클라우드·Cloudflare 전부 필요 없다.
이 PC에서 `localhost`로만 띄우고, 실제 채권을 넣어 우리 장부와 숫자를 맞춰 본다.

## 왜 로컬이면 충분한가

- 외부에 열지 않으므로 [남아 있는 인증 구멍](../src/main/java/com/jdend/erp/config/TenantFilter.java#L72)이 문제가 되지 않는다.
  (반대로 말하면 **이 구멍을 막기 전에는 외부에 열면 안 된다.**)
- MySQL 3306은 Windows 방화벽에서 이미 차단되어 있다(배포 문서 §7). 로컬 접속은 영향 없다.
- 서버비 0원. 켜 둔 동안만 돌면 되고, 껐다 켜도 데이터는 MySQL에 남는다.

## 준비물 (이미 다 있음)

| | 상태 |
|---|---|
| MySQL 8.0 (port 3306) | 설치·실행 중 |
| JDK 17 | `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` |
| 소스 | 이 저장소 |

---

## 절차

### 1단계 — DB 2개 만들기

```
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p < local\1-create-databases.sql
```

- `loan_auth` — 로그인 계정·회사 목록이 있는 운영 DB
- `loan_erp` — **템플릿 DB.** 새 회사를 만들 때 이 DB의 테이블 구조를 복사해 간다

### 2단계 — 템플릿 DB에 스키마 넣기 (여기가 함정)

`ddl-auto`는 **auth DB와 이미 존재하는 `loan_company_*`에만** 돌고 `loan_erp`에는 돌지 않는다.
템플릿을 빈 채로 두면 [copySchemaIfEmpty](../src/main/java/com/jdend/erp/config/TenantDatabaseService.java#L52)가
복사할 테이블을 0개 찾고, 그 다음 `ensureFinancialStatementAccounts`가 없는 테이블을 조회하다 터진다.
→ **회사 생성이 실패한다.** 운영 문서에 "신규 회사 생성 시 계정이 0건으로 복제되는 버그"로 적혀 있던 게 이것이다.

한 번만 이렇게 우회한다 — `run-local.bat`의 DB 이름을 `loan_erp`로 바꿔 앱을 한 번 띄운다.

```
set DB_URL_AUTH=jdbc:mysql://localhost:3306/loan_erp?...
```

로그에 `[DefaultAccountSeeder] template DB(loan_erp) 재무제표 기본 계정 시딩 완료`가 뜨면 끝이다.
앱을 끄고 **DB 이름을 `loan_auth`로 되돌린다.** 이후로는 건드릴 일이 없다.

> 이때 `loan_auth`에도 스키마가 필요한데, 그건 이후 정상 기동 때 `ddl-auto`가 자동으로 만든다.

### 3단계 — 운영자 계정 1건

`local/3-create-admin.sql`의 비밀번호를 본인 것으로 바꾸고 실행한다.

```
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p loan_auth < local\3-create-admin.sql
```

**비밀번호는 평문으로 넣어도 된다.** 첫 로그인 성공 시 BCrypt로 자동 재암호화된다
([AuthService.isHashed](../src/main/java/com/jdend/erp/auth/service/AuthService.java#L343) 분기).

### 4단계 — 실행

`run-local.bat.example`를 `run-local.bat`로 복사하고 비밀번호를 채운 뒤 실행한다.
(`run-local.bat`는 `.gitignore` 처리되어 커밋되지 않는다)

→ http://localhost:8080/login.html

### 5단계 — 플랜비대부 회사 계정 만들기

1. 3단계 운영자 계정으로 로그인
2. http://localhost:8080/admin_users.html
3. 회사 추가 — 통합 아이디 `planb`, 회사명 `플랜비대부`

이때 [adminCreate](../src/main/java/com/jdend/erp/auth/service/AuthService.java#L203)가
`loan_company_planb` DB를 만들고 템플릿에서 스키마와 계정과목을 복사한다.
같은 아이디/비밀번호의 회사관리자 사용자도 함께 자동 생성된다.

4. 로그아웃 → `planb` 계정으로 로그인 (**사용자 아이디/비밀번호 칸은 비워둔다**)

---

## 실데이터 넣기

### 순서

1. **고객** — `고객관리 > 고객등록`. 엑셀 일괄등록 지원 (`/api/customers/bulk-upload/template`)
2. **대출** — `여신관리 > 대출등록`. 엑셀 일괄등록 지원 (`/api/contracts/bulk-upload/template`)

   컬럼 14개:
   ```
   고객번호 / 고객구분 / 대출구분 / 대출금 / 이자율 / 연체이율 / 연체이자부과
   상환방식 / 실행일 / 시작일자 / 종료일자 / 납입일자 / 회차수 / 비고
   ```
   등록하면 **상환스케줄이 자동 생성된다**(`ContractService.create` → `scheduleAutoGen.ensureGenerated`).
   회차별 원금·이자를 따로 넣을 필요가 없다.

3. **과거 수납** — `수납관리 > 수납등록`에 **날짜순으로** 입력
4. **법적절차 비용** — 추심 중인 채권이 있으면 `채권관리 > 법적절차`에 사건과 비용을 등록

### 이미 진행 중인 채권을 어떻게 넣나 — 이게 핵심이다

**개시 잔액을 직접 입력하지 않는다.** 실제 실행일·원금·이율로 그대로 등록하고,
그 뒤에 받은 수납을 날짜순으로 입력하면 된다.

[RepaymentPostingService.recompute](../src/main/java/com/jdend/erp/loan/repayment/RepaymentPostingService.java)가
수납이 하나 들어올 때마다 **그 채권의 수납 전체를 처음부터 다시 흘려보내(replay) 충당을 재계산**한다.
증분으로 더하고 빼지 않기 때문에, 과거 수납을 순서대로 넣으면 현재 잔여원금이 저절로 맞아떨어진다.

그래서 시범운영이 곧 검증이 된다:

> 2024년에 실행한 채권을 등록하고 그때부터의 수납을 다 넣었을 때,
> **화면의 잔여원금이 우리 장부의 잔액과 같은가?**

같으면 스케줄 생성·변제충당·일할 정산이 다 맞는 것이다. 다르면 어디서 갈리는지가 바로 드러난다.

### 대조해 볼 것 (한 달 동안)

| 확인 | 어디서 | 무엇과 비교 |
|---|---|---|
| 잔여원금 | 채권관리 > 채권현황 | 실제 장부 잔액 |
| 회차별 원금·이자 | 여신관리 > 상환스케줄 | 실제 상환표 |
| 변제충당 배분 | 수납등록 후 전표 | 실제로 이자/원금에 얼마씩 넣었는지 |
| 연체이자 | 채권관리 > 연체현황 | 손으로 계산한 일할 금액 |
| 이자수익 합계 | 회계관리 > 손익계산서 | 실제 장부의 이자수익 |
| 영업이익 | 회계관리 > 손익계산서 | (신규 — 기존 장부에 없던 단계) |

`플랜비대부 상반기내역.xlsx`(209건 분개)가 이미 분석돼 있으니, **2026 상반기를 그대로 재입력해
손익계산서가 그 장부와 맞는지 보는 것**이 가장 빠른 검증이다.

---

## 주의

### 백업 — 실데이터를 넣는 순간부터 필수

```
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" -u root -p ^
  --databases loan_auth loan_erp loan_company_planb ^
  --single-transaction --routines > backup_YYYYMMDD.sql
```

한 달 시범운영이라도 매일 받아 두는 게 맞다. 채권 장부라 한 번 날리면 복구할 방법이 없다.

### 전표 승인을 잊지 말 것

자동전표가 **'대기'** 상태로 생성된다. 승인하지 않으면 **재무제표·자금일보에서 빠진다.**
숫자가 안 맞으면 이것부터 확인한다. → `회계관리 > 전표등록 > 전표승인`

### 개인신용정보

채무자의 주민등록번호·연체 이력이 이 PC에 들어온다. 최소한:
- Windows 로그인 비밀번호 설정, 화면 잠금
- BitLocker 등 디스크 암호화 검토
- 백업 파일도 같이 보호(백업이 평문으로 바탕화면에 있으면 의미가 없다)

### 절대 하지 말 것

- **이 상태로 외부에 열지 말 것.** 인증 구멍이 남아 있다. Cloudflare Tunnel로 노출하는 것도 아직 안 된다.
- `sql/discard_rentcar_tenants.sql`은 이번 시범운영과 무관하다. 실행하지 말 것.
