-- 로컬 시범운영 1단계: 데이터베이스 생성
--
-- 실행:
--   "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p < local\1-create-databases.sql
--
-- 만드는 것 2개:
--   loan_auth  — 운영 DB. 로그인 계정·회사 목록·구독·세무상담이 여기 있다.
--   loan_erp   — 템플릿 DB. 새 회사를 만들 때 이 DB의 테이블 구조를 복사해 간다.
--
-- 회사별 DB(loan_company_*)는 앱이 로그인 시점에 자동으로 만든다. 손댈 필요 없다.

CREATE DATABASE IF NOT EXISTS `loan_auth`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `loan_erp`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SELECT schema_name AS '생성된 DB'
  FROM information_schema.schemata
 WHERE schema_name IN ('loan_auth', 'loan_erp');
