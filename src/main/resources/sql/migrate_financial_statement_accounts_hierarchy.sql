-- 재무제표관리 계층구조(대분류>중분류>소분류>소소분류) 도입 마이그레이션
--
-- * 이 프로젝트는 ddl-auto=none / 마이그레이션 툴이 없어서 자동으로 실행되지 않습니다.
--   필요한 회사 DB(erp, erp_company_a, erp_company_b, erp_company_c)에 모두 직접 실행해 주세요.
-- * 기존 financial_statement_accounts의 26개 계정(id)은 그대로 보존하고 컬럼만 채웁니다
--   (전표는 계정명을 문자열로 저장하므로 안전하지만, getVoucherRows 등 id 참조 호환을 위해 UPDATE로 처리).
-- * 계정코드 규칙: 대분류 2자리(자산10/부채20/자본30/수익40/비용50) + 레벨마다 부모코드+2자리 순번.
-- * INSERT IGNORE를 사용해 account_code가 이미 있으면 건너뜁니다(재실행 안전).

-- ========== 0) 스키마 변경 ==========
ALTER TABLE financial_statement_accounts
  ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'ASSET' AFTER statement_type,
  ADD COLUMN level TINYINT NOT NULL DEFAULT 1 AFTER category,
  ADD COLUMN parent_id BIGINT NULL AFTER level,
  ADD COLUMN is_postable VARCHAR(10) NOT NULL DEFAULT '사용' AFTER is_active,
  ADD CONSTRAINT fk_fsa_parent FOREIGN KEY (parent_id) REFERENCES financial_statement_accounts(id);

ALTER TABLE financial_statement_accounts DROP INDEX uk_stmt_code;
ALTER TABLE financial_statement_accounts ADD CONSTRAINT uk_fsa_account_code UNIQUE (account_code);

CREATE INDEX idx_fsa_parent_id ON financial_statement_accounts(parent_id);
CREATE INDEX idx_fsa_category ON financial_statement_accounts(category);

-- ========== 1) 대분류 (level 1, parent_id NULL) ==========
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
VALUES
  ('bs', 'ASSET',     1, NULL, '10', '자산', '자산', 1, '사용', '미사용'),
  ('bs', 'LIABILITY', 1, NULL, '20', '부채', '부채', 2, '사용', '미사용'),
  ('bs', 'EQUITY',    1, NULL, '30', '자본', '자본', 3, '사용', '미사용'),
  ('is', 'REVENUE',   1, NULL, '40', '수익', '수익', 4, '사용', '미사용'),
  ('is', 'EXPENSE',   1, NULL, '50', '비용', '비용', 5, '사용', '미사용');

-- ========== 2) 중분류 (level 2) ==========
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 2, id, '1001', '현금 및 예치금', '현금 및 예치금', 1, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '10';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 2, id, '1002', '유가증권 및 투자자산', '유가증권 및 투자자산', 2, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '10';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 2, id, '1003', '대출채권', '대출채권', 3, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '10';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 2, id, '1004', '유형자산', '유형자산', 4, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '10';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 2, id, '1005', '기타자산', '기타자산', 5, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '10';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 2, id, '2001', '유동부채', '유동부채', 1, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '20';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 2, id, '2002', '비유동부채', '비유동부채', 2, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '20';

-- 주의: 자본금/자본잉여금/이익잉여금은 중분류를 따로 만들지 않고, 기존 26개 계정 중 동일한 이름의
-- 행을 5번 UPDATE에서 바로 레벨2(코드 3001/3002/3003)로 승격시킨다. 여기서 별도로 INSERT하면
-- 이름이 같은 행이 두 개 생겨 재무상태표 집계가 이중으로 합산되는 버그가 생기므로 절대 추가하지 말 것.

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 2, id, '4001', '매출', '매출', 1, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '40';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 2, id, '4002', '영업외수익', '영업외수익', 2, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '40';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 2, id, '5001', '매출원가', '매출원가', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '50';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 2, id, '5002', '판매비와관리비', '판매비와관리비', 2, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '50';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 2, id, '5003', '영업외비용', '영업외비용', 3, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '50';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 2, id, '5004', '법인세', '법인세', 4, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '50';

-- ========== 3) 소분류 (level 3, 신규분만 - 기존 26개는 5번에서 UPDATE) ==========
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100102', '현금성자산', '현금 및 예치금', 2, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '1001';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100201', '유가증권', '유가증권 및 투자자산', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100202', '투자자산', '유가증권 및 투자자산', 2, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100301', '단기대여금', '대출채권', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1003';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100302', '장기대여금', '대출채권', 2, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1003';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100401', '차량운반구', '유형자산', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1004';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100402', '집기/가구비품', '유형자산', 2, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1004';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100501', '가지급금', '기타자산', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100502', '임차보증금', '기타자산', 2, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100505', '선급비용', '기타자산', 5, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100507', '기타', '기타자산', 7, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200104', '미지급비용', '유동부채', 4, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '2001';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400103', '렌트료수익', '매출', 3, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '4001';

-- 주의: 매출원가(5001)/법인세(5004)도 위 중분류 INSERT에서 이미 is_postable='사용'으로 만들었으므로
-- 여기서 같은 이름의 소분류를 또 만들면 안 된다(이중 합산 버그).
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500205', '상여', '판매비와관리비', 5, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500206', '복리후생비', '판매비와관리비', 6, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500207', '여비교통비', '판매비와관리비', 7, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500208', '접대비', '판매비와관리비', 8, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500209', '통신비', '판매비와관리비', 9, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500210', '수도광열비', '판매비와관리비', 10, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500211', '세금과공과', '판매비와관리비', 11, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500212', '지급수수료', '판매비와관리비', 12, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500213', '임차료', '판매비와관리비', 13, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500214', '미상각잔액', '판매비와관리비', 14, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';

-- ========== 4) 소소분류 (level 4, 신규분 - 대손충당금 등 contra 계정) ==========
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 4, id, '10030101', '단기대여금 대손충당금', '대출채권', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '100301';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 4, id, '10030201', '장기대여금 대손충당금', '대출채권', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '100302';

-- ========== 5) 기존 26개 계정을 새 계층 위치로 UPDATE (id 보존, 이름 변경 없음) ==========
-- parent_id IS NULL 조건으로 재실행 안전성 확보(이미 마이그레이션된 행은 다시 안 건드림)

-- 자산
UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='ASSET', a.level=4, a.parent_id=p.id, a.account_code='10010201', a.is_postable='사용'
WHERE a.account_name='현금' AND a.parent_id IS NULL AND p.account_code='100102';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='ASSET', a.level=3, a.parent_id=p.id, a.account_code='100101', a.is_postable='사용'
WHERE a.account_name='보통예금' AND a.parent_id IS NULL AND p.account_code='1001';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='ASSET', a.level=4, a.parent_id=p.id, a.account_code='10010202', a.is_postable='사용'
WHERE a.account_name='정기예금' AND a.parent_id IS NULL AND p.account_code='100102';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='ASSET', a.level=3, a.parent_id=p.id, a.account_code='100503', a.is_postable='사용'
WHERE a.account_name='미수금' AND a.parent_id IS NULL AND p.account_code='1005';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='ASSET', a.level=3, a.parent_id=p.id, a.account_code='100504', a.is_postable='사용'
WHERE a.account_name='선급금' AND a.parent_id IS NULL AND p.account_code='1005';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='ASSET', a.level=3, a.parent_id=p.id, a.account_code='100403', a.is_postable='사용'
WHERE a.account_name='렌트자산' AND a.parent_id IS NULL AND p.account_code='1004';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='ASSET', a.level=3, a.parent_id=p.id, a.account_code='100404', a.is_postable='사용'
WHERE a.account_name='감가상각누계액' AND a.parent_id IS NULL AND p.account_code='1004';

-- 부채
UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='LIABILITY', a.level=3, a.parent_id=p.id, a.account_code='200101', a.is_postable='사용'
WHERE a.account_name='미지급금' AND a.parent_id IS NULL AND p.account_code='2001';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='LIABILITY', a.level=3, a.parent_id=p.id, a.account_code='200102', a.is_postable='사용'
WHERE a.account_name='선수금' AND a.parent_id IS NULL AND p.account_code='2001';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='LIABILITY', a.level=3, a.parent_id=p.id, a.account_code='200201', a.is_postable='사용'
WHERE a.account_name='차입금' AND a.parent_id IS NULL AND p.account_code='2002';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='LIABILITY', a.level=3, a.parent_id=p.id, a.account_code='200202', a.is_postable='사용'
WHERE a.account_name='보증금' AND a.parent_id IS NULL AND p.account_code='2002';

-- 자본: 별도 중분류 INSERT 없이, 기존 행을 바로 레벨2(코드 3001/3002/3003)로 승격
UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EQUITY', a.level=2, a.parent_id=p.id, a.account_code='3001', a.is_postable='사용'
WHERE a.account_name='자본금' AND a.parent_id IS NULL AND p.account_code='30';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EQUITY', a.level=2, a.parent_id=p.id, a.account_code='3002', a.is_postable='사용'
WHERE a.account_name='자본잉여금' AND a.parent_id IS NULL AND p.account_code='30';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EQUITY', a.level=2, a.parent_id=p.id, a.account_code='3003', a.is_postable='사용'
WHERE a.account_name='이익잉여금' AND a.parent_id IS NULL AND p.account_code='30';

-- 수익
UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='REVENUE', a.level=3, a.parent_id=p.id, a.account_code='400101', a.is_postable='사용'
WHERE a.account_name='렌트수익' AND a.parent_id IS NULL AND p.account_code='4001';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='REVENUE', a.level=3, a.parent_id=p.id, a.account_code='400102', a.is_postable='사용'
WHERE a.account_name='매각수익' AND a.parent_id IS NULL AND p.account_code='4001';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='REVENUE', a.level=3, a.parent_id=p.id, a.account_code='400201', a.is_postable='사용'
WHERE a.account_name='이자수익' AND a.parent_id IS NULL AND p.account_code='4002';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='REVENUE', a.level=3, a.parent_id=p.id, a.account_code='400202', a.is_postable='사용'
WHERE a.account_name='기타수익' AND a.parent_id IS NULL AND p.account_code='4002';

-- 비용
UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EXPENSE', a.level=3, a.parent_id=p.id, a.account_code='500201', a.is_postable='사용'
WHERE a.account_name='급여' AND a.parent_id IS NULL AND p.account_code='5002';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EXPENSE', a.level=3, a.parent_id=p.id, a.account_code='500202', a.is_postable='사용'
WHERE a.account_name='감가상각비' AND a.parent_id IS NULL AND p.account_code='5002';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EXPENSE', a.level=3, a.parent_id=p.id, a.account_code='500203', a.is_postable='사용'
WHERE a.account_name='차량유지비' AND a.parent_id IS NULL AND p.account_code='5002';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EXPENSE', a.level=3, a.parent_id=p.id, a.account_code='500204', a.is_postable='사용'
WHERE a.account_name='보험료' AND a.parent_id IS NULL AND p.account_code='5002';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EXPENSE', a.level=3, a.parent_id=p.id, a.account_code='500301', a.is_postable='사용'
WHERE a.account_name='이자비용' AND a.parent_id IS NULL AND p.account_code='5003';

UPDATE financial_statement_accounts a, financial_statement_accounts p
SET a.category='EXPENSE', a.level=3, a.parent_id=p.id, a.account_code='500302', a.is_postable='사용'
WHERE a.account_name='기타비용' AND a.parent_id IS NULL AND p.account_code='5003';
