-- 렌터카 계정과목 -> 대부업 계정과목 전환 스크립트
--
-- 신규 테넌트 DB는 seed_financial_statement_accounts_fresh.sql 이 이미 대부업 기준으로
-- 계정을 만들어 주므로 이 스크립트가 필요 없다. 렌터카 기준으로 먼저 생성된 DB를
-- 대부업으로 돌려쓸 때만 1회 실행한다.
--
-- 핵심: 이자수익을 영업/영업외로 분리한다.
--   400101 이자수익      -> 영업수익 (대출채권 이자, 영업이익에 반영)
--   400201 예금이자수익  -> 영업외수익 (은행 예금이자, 영업이익에 미반영)

-- ========== 1) 중분류 명칭 ==========
UPDATE financial_statement_accounts
   SET account_name = '영업수익', account_type = '영업수익'
 WHERE account_code = '4001';

-- ========== 2) 영업수익 소분류 ==========
UPDATE financial_statement_accounts
   SET account_name = '이자수익', account_type = '영업수익', is_active = '사용', is_postable = '사용'
 WHERE account_code = '400101';

UPDATE financial_statement_accounts
   SET account_name = '연체이자수익', account_type = '영업수익', is_active = '사용', is_postable = '사용'
 WHERE account_code = '400102';

UPDATE financial_statement_accounts
   SET account_name = '상각채권추심이익', account_type = '영업수익', is_active = '사용', is_postable = '사용'
 WHERE account_code = '400103';

INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400104', '중도상환수수료수익', '영업수익', 4, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '4001';

-- ========== 3) 영업외수익 ==========
-- 기존 '이자수익'(400201)은 예금이자 성격이므로 이름을 바꿔 400101과 구분한다.
UPDATE financial_statement_accounts
   SET account_name = '예금이자수익', account_type = '영업외수익'
 WHERE account_code = '400201';

-- 렌터카 전용 계정
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '400203'; -- 해지수수료수익

-- ========== 4) 비용 ==========
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500215', '대손상각비', '판매비와관리비', 15, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500216', '사대보험료', '판매비와관리비', 16, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500217', '관리비', '판매비와관리비', 17, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '5002';

-- ========== 5) 자산/부채 ==========
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200105', '가수금', '유동부채', 5, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '2001';

UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '100301';   -- 단기대여금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '100302';   -- 장기대여금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '10030101'; -- 단기대여금 대손충당금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '10030201'; -- 장기대여금 대손충당금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '100501';   -- 가지급금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '100502';   -- 임차보증금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '500213';   -- 임차료

-- ========== 6) 렌터카 전용 계정 비활성화 ==========
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '100401'; -- 차량운반구
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '100403'; -- 렌트자산
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '100404'; -- 감가상각누계액
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '500202'; -- 감가상각비
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '500203'; -- 차량유지비
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '500214'; -- 미상각잔액

-- ========== 7) 부가세 계정 제거 (대부업은 면세사업) ==========
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code IN ('100506', '200103');
