-- financial_statement_accounts 테이블이 완전히 비어있는 "신규 설치" DB에 한 번에 전체 계층(대/중/소/소소분류)을
-- 채워 넣는 스크립트입니다. migrate_financial_statement_accounts_hierarchy.sql은 기존 평면 데이터를 옮기는
-- 용도(UPDATE 기반)라서 빈 테이블에는 그대로 안 맞습니다 - 이 스크립트는 전부 INSERT로 처리합니다.
-- (테이블/컬럼 자체는 Hibernate ddl-auto로 이미 생성되어 있다는 전제, 즉 ALTER는 포함하지 않습니다.)

-- ========== 1) 대분류 ==========
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
VALUES
  ('bs', 'ASSET',     1, NULL, '10', '자산', '자산', 1, '사용', '미사용'),
  ('bs', 'LIABILITY', 1, NULL, '20', '부채', '부채', 2, '사용', '미사용'),
  ('bs', 'EQUITY',    1, NULL, '30', '자본', '자본', 3, '사용', '미사용'),
  ('is', 'REVENUE',   1, NULL, '40', '수익', '수익', 4, '사용', '미사용'),
  ('is', 'EXPENSE',   1, NULL, '50', '비용', '비용', 5, '사용', '미사용');

-- ========== 2) 중분류 ==========
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

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'EQUITY', 2, id, '3001', '자본금', '자본금', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '30';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'EQUITY', 2, id, '3002', '자본잉여금', '자본잉여금', 2, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '30';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'EQUITY', 2, id, '3003', '이익잉여금', '이익잉여금', 3, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '30';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 2, id, '4001', '영업수익', '영업수익', 1, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '40';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 2, id, '4002', '영업외수익', '영업외수익', 2, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '40';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 2, id, '5001', '영업비용', '영업비용', 1, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '50';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 2, id, '5002', '판매비와관리비', '판매비와관리비', 2, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '50';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 2, id, '5003', '영업외비용', '영업외비용', 3, '사용', '미사용' FROM financial_statement_accounts WHERE account_code = '50';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 2, id, '5004', '법인세', '법인세', 4, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '50';

-- ========== 3) 소분류 ==========
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100101', '보통예금', '현금 및 예치금', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '1001';
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
SELECT 'bs', 'ASSET', 3, id, '100403', '렌트자산', '유형자산', 3, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '1004';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100404', '감가상각누계액', '유형자산', 4, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '1004';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100501', '가지급금', '기타자산', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100502', '임차보증금', '기타자산', 2, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100503', '미수금', '기타자산', 3, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100504', '선급금', '기타자산', 4, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100505', '선급비용', '기타자산', 5, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100507', '기타', '기타자산', 7, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '1005';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200101', '미지급금', '유동부채', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '2001';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200102', '선수금', '유동부채', 2, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '2001';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200104', '미지급비용', '유동부채', 4, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '2001';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200201', '차입금', '비유동부채', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '2002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200202', '보증금', '비유동부채', 2, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '2002';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400101', '이자수익', '영업수익', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '4001';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400102', '연체이자수익', '영업수익', 2, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '4001';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400103', '상각채권추심이익', '영업수익', 3, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '4001';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400201', '예금이자수익', '영업외수익', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '4002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400202', '기타수익', '영업외수익', 2, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '4002';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500201', '급여', '판매비와관리비', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500202', '감가상각비', '판매비와관리비', 2, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500203', '차량유지비', '판매비와관리비', 3, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500204', '보험료', '판매비와관리비', 4, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
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
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500101', '이자비용', '영업비용', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5001';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500302', '기타비용', '영업외비용', 2, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5003';

-- 대부업 업무에 필요한 추가 계정
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400104', '중도상환수수료수익', '영업수익', 4, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '4001';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500215', '대손상각비', '판매비와관리비', 15, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500216', '사대보험료', '판매비와관리비', 16, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500217', '관리비', '판매비와관리비', 17, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5002';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200105', '가수금', '유동부채', 5, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '2001';

INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500303', '법무비용', '영업외비용', 3, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '5003';

-- ========== 대부업 계정 활성화 ==========
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '100301';   -- 단기대여금 (대출채권)
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '100302';   -- 장기대여금 (대출채권)
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '100501';   -- 가지급금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '100502';   -- 임차보증금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '200104';   -- 미지급비용
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '500211';   -- 세금과공과
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '500212';   -- 지급수수료
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '500213';   -- 임차료
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '10030101'; -- 단기대여금 대손충당금
UPDATE financial_statement_accounts SET is_active = '사용' WHERE account_code = '10030201'; -- 장기대여금 대손충당금

-- ========== 렌터카 전용 계정 비활성화 ==========
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '100401'; -- 차량운반구
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '100403'; -- 렌트자산
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '100404'; -- 감가상각누계액
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '500202'; -- 감가상각비
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '500203'; -- 차량유지비
UPDATE financial_statement_accounts SET is_active = '미사용' WHERE account_code = '500214'; -- 미상각잔액

-- ========== 4) 소소분류 (대손충당금) ==========
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 4, id, '10010201', '현금', '현금 및 예치금', 1, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '100102';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 4, id, '10010202', '정기예금', '현금 및 예치금', 2, '사용', '사용' FROM financial_statement_accounts WHERE account_code = '100102';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 4, id, '10030101', '단기대여금 대손충당금', '대출채권', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '100301';
INSERT IGNORE INTO financial_statement_accounts (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 4, id, '10030201', '장기대여금 대손충당금', '대출채권', 1, '미사용', '사용' FROM financial_statement_accounts WHERE account_code = '100302';
