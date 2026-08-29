-- 계정과목을 대부업체 기준으로 정비
--
-- 배경:
--   렌터카 ERP 계정구조를 이어받다 보니 재무제표관리 화면에 대부업과 무관한 계정이 남고,
--   정작 대부업체가 매달 쓰는 계정(예수금·소모품비 등)은 빠져 있었다.
--
-- 이 스크립트가 하는 일:
--   A. 렌터카 전용 허수 계정 제거
--   B. 사무실을 운영하면 실제로 쓰는데 '미사용'으로 꺼져 있던 계정 활성화
--   C. 대부업체에 필요한데 아예 없던 계정 추가
--   D. 법인세 계층 구조 정리
--
-- 적용 대상: 템플릿 DB(loan_erp) + 모든 테넌트 DB(loan_company_*)
--   USE 문을 대상 DB 로 바꿔가며 실행한다.
--
-- 안전성: INSERT IGNORE / 조건부 UPDATE 라 여러 번 실행해도 문제없다.
--   단 A 의 DELETE 는 해당 계정을 쓴 전표가 없을 때만 지운다.

-- ========== A) 렌터카 전용 허수 계정 제거 ==========
-- '미상각잔액'은 렌터카 차량을 매각할 때 대차를 억지로 맞추려고 쓰던 계정이다.
-- 대부업에는 존재 이유가 없다. 다만 이미 이 계정으로 끊은 전표가 있으면 지우지 않는다.
DELETE FROM financial_statement_accounts
 WHERE account_code = '500214'
   AND NOT EXISTS (
     SELECT 1 FROM voucher_lines vl WHERE vl.account_code = '500214'
   );

-- 차량운반구(100401)·차량유지비(500203)는 남긴다.
-- 대부업체도 업무용 차량을 자산으로 잡는 경우가 있어, 필요하면 화면에서 '사용'으로 켜면 된다.

-- ========== B) 사무실 운영 계정 활성화 ==========
-- 급여·사대보험료를 쓰는 회사라면 아래는 실제로 발생한다. 꺼져 있으면 전표에서 고를 수 없다.
UPDATE financial_statement_accounts SET is_active = '사용'
 WHERE account_code IN (
   '100402',  -- 집기/가구비품 (사무 비품)
   '100404',  -- 감가상각누계액 (비품 감가상각)
   '100505',  -- 선급비용
   '500202',  -- 감가상각비
   '500205',  -- 상여
   '500206',  -- 복리후생비
   '500207',  -- 여비교통비
   '500208',  -- 접대비
   '500209',  -- 통신비
   '500210'   -- 수도광열비
 );

-- 대손충당금 — 대손상각 시 반드시 상계에 쓰는 계정인데 '미사용'으로 남아 있었다.
-- 시드 파일에서 활성화 UPDATE 가 해당 INSERT 보다 앞서 있어 효과가 없었다(순서 버그).
UPDATE financial_statement_accounts SET is_active = '사용'
 WHERE account_code IN ('10030101','10030201');

-- ========== C) 대부업에 필요한 계정 추가 ==========

-- C-1. 미수이자 — 대출채권에서 발생했지만 아직 못 받은 이자.
--      현금주의라 평소에는 쓰지 않으므로 기본 '미사용'. 결산에 필요하면 켠다.
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'ASSET', 3, id, '100303', '미수이자', '대출채권', 3, '미사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '1003';

-- C-2. 예수금 — 급여에서 원천징수한 소득세·지방세와 4대보험 본인부담분.
--      급여를 지급하는 순간 반드시 필요한데 빠져 있었다.
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'bs', 'LIABILITY', 3, id, '200106', '예수금', '유동부채', 6, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '2001';

-- C-3. 대손충당금환입 — 쌓아둔 대손충당금을 되돌릴 때. 영업수익으로 본다.
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'REVENUE', 3, id, '400105', '대손충당금환입', '영업수익', 5, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '4001';

-- C-4. 소모품비 · 광고선전비 — 사무용품, 대부업 광고(대부업법상 표시광고 비용).
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500218', '소모품비', '판매비와관리비', 18, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '5002';

INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500219', '광고선전비', '판매비와관리비', 19, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '5002';

-- C-5. 영업외비용 아래가 '기타비용' 하나뿐이었다. 이자비용을 영업비용으로 옮기면서 비었다.
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500301', '기부금', '영업외비용', 1, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '5003';

-- ========== D) 법인세 계층 정리 ==========
-- 5004 '법인세'는 중분류인데 전표 입력이 열려 있었다. 다른 중분류(4001·5001·5002)는 다 막혀 있다.
-- 중분류는 막고 그 아래 실제 계상 계정을 만든다.
INSERT IGNORE INTO financial_statement_accounts
  (statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable)
SELECT 'is', 'EXPENSE', 3, id, '500401', '법인세비용', '법인세', 1, '사용', '사용'
  FROM financial_statement_accounts WHERE account_code = '5004';

-- 위 INSERT 로 500401 이 보장되므로 그냥 막으면 된다.
UPDATE financial_statement_accounts SET is_postable = '미사용' WHERE account_code = '5004';

-- ========== 확인 ==========
SELECT account_code, account_name, account_type, level, is_active, is_postable
  FROM financial_statement_accounts
 WHERE account_code IN ('100303','200106','400105','500218','500219','500301','500401','5004','500214')
 ORDER BY account_code;
