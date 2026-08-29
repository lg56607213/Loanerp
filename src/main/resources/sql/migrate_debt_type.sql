-- 계약에 채권성격(debt_type) 추가 — 개인채무자보호법 적용 판정용
--
-- 배경:
--   개인금융채권의 관리 및 개인금융채무자의 보호에 관한 법률은
--     ① 최초원금 5,000만원 미만  ② 개인 채무자  ③ 개인금융채권
--   세 가지를 모두 만족할 때 적용된다. 적용되면 기한이익상실이 나도
--   원래 납기일이 도래하지 않은 원금에는 연체가산이자를 붙일 수 없다.
--
--   ①은 loan_amount, ②는 customer_type 으로 이미 판정되는데 ③이 없었다.
--
-- 적용 대상: 모든 테넌트 DB(loan_company_*) + 템플릿(loan_erp)
--   ddl-auto 가 컬럼을 만들어 주므로 앱을 한 번 띄운 뒤 아래 UPDATE 만 돌리면 된다.

-- 컬럼이 없으면 만든다 (앱을 아직 안 띄운 경우 대비)
-- MySQL 은 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 를 지원하지 않는다(MariaDB 문법).
-- information_schema 로 존재 여부를 보고 동적 SQL 로 처리한다.
SET @ddl := (SELECT IF(COUNT(*) > 0,
  'SELECT ''debt_type 컬럼이 이미 있습니다''',
  'ALTER TABLE contracts ADD COLUMN debt_type VARCHAR(20) NULL'
) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'contracts' AND column_name = 'debt_type');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 기존 채권 기본값.
-- 개인이면서 최초원금 5,000만원 미만이면 개인금융채권으로 본다.
-- 대부업 여신은 대부분 여기 해당하고, 잘못 잡았을 때 채무자에게 불리해지는 쪽
-- (기타로 두어 가산이자를 붙이는 쪽)보다 안전하다. 실제와 다르면 화면에서 바꾼다.
UPDATE contracts
   SET debt_type = CASE
         WHEN (customer_type IS NULL OR customer_type = '개인')
              AND COALESCE(loan_amount, 0) < 50000000 THEN '개인금융채권'
         ELSE '기타'
       END
 WHERE debt_type IS NULL OR debt_type = '';

-- 확인
SELECT contract_number, customer_type, FORMAT(loan_amount, 0) AS 대출금, debt_type,
       CASE WHEN customer_type = '개인' AND loan_amount < 50000000 AND debt_type = '개인금융채권'
            THEN '보호법 적용' ELSE '비적용' END AS 판정
  FROM contracts
 ORDER BY id;
