-- 로컬 시범운영 3단계: 최초 운영자 계정 1건
--
-- 실행:
--   "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p loan_auth < local\3-create-admin.sql
--
-- ※ 2단계(템플릿 DB 스키마 생성)를 먼저 끝내야 login_users 테이블이 존재한다.
--
-- 비밀번호를 평문으로 넣어도 된다. AuthService 가 첫 로그인 성공 시 BCrypt 로
-- 자동 재암호화한다(AuthService.java 의 isHashed 분기). 해시를 직접 만들 필요가 없다.
--
-- 아래 비밀번호는 반드시 본인 것으로 바꿔서 실행할 것.

INSERT INTO login_users
  (login_id, login_password, company_name, target_db, role,
   is_active, tax_consultation_enabled, maintenance_enabled, created_at)
VALUES
  ('admin', '바꿔주세요', 'JDEND 운영자', 'auth', 'PLATFORM_ADMIN',
   1, 1, 1, NOW())
ON DUPLICATE KEY UPDATE login_id = login_id;

SELECT id, login_id, company_name, role, is_active FROM login_users;
