-- 무료체험 신청서 컬럼 전환: vehicle_count -> loan_balance_scale
--
-- 배경:
--   렌터카 ERP 시절 신청서에는 '차량 보유 대수'를 받았다. 대부업에서는 의미가 없어
--   '대출채권 잔액 규모'로 바꿨다(예: 5억 미만 / 5-20억 / 20-50억 / 50억 이상).
--
-- 적용 대상 DB: 인증(auth) DB 1곳. 테넌트 DB에는 이 테이블이 없다.
--
-- 주의:
--   ddl-auto=update 는 새 컬럼(loan_balance_scale)을 추가만 하고 기존 컬럼은 남긴다.
--   앱을 한 번 띄워 새 컬럼이 생긴 뒤에 아래를 실행한다.

-- 1) 새 컬럼이 없으면 만든다 (앱을 아직 안 띄운 경우 대비)
-- MySQL 은 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 를 지원하지 않는다(MariaDB 문법).
-- information_schema 로 존재 여부를 보고 동적 SQL 로 처리한다.
SET @ddl := (SELECT IF(COUNT(*) > 0,
  'SELECT ''loan_balance_scale 컬럼이 이미 있습니다''',
  'ALTER TABLE company_applications ADD COLUMN loan_balance_scale VARCHAR(30) NULL'
) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'company_applications' AND column_name = 'loan_balance_scale');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 기존 값 이관 — 차량 대수 표기는 대부업에서 의미가 없으므로 원문을 그대로 옮겨
--    이력만 보존한다. 신규 신청부터 새 구간 값이 들어온다.
UPDATE company_applications
   SET loan_balance_scale = vehicle_count
 WHERE loan_balance_scale IS NULL
   AND vehicle_count IS NOT NULL;

-- 3) 이관 확인 후 옛 컬럼 제거
--    (확인 쿼리: SELECT id, vehicle_count, loan_balance_scale FROM company_applications;)
ALTER TABLE company_applications DROP COLUMN vehicle_count;
