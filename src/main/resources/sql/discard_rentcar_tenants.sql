-- 렌터카 테넌트 폐기 (사장 결정: 분리 유지가 아니라 폐기)
--
-- 배경:
--   대부업 ERP는 테넌트 DB 접두어를 `loan_company_`, 템플릿 DB를 `loan_erp` 로 쓴다.
--   렌터카 ERP는 `erp_company_*` / `erp` 를 썼다. 코드상 두 계열은 이미 완전히 갈라져
--   있어(TenantDatabaseService.validateDbName 이 항상 loan_company_ 를 붙인다) 대부업
--   ERP가 렌터카 DB를 건드릴 일은 없다. 남은 것은 '치우는 일'뿐이다.
--
-- ※ 이 스크립트는 데이터를 지운다. 반드시 아래 순서를 지킬 것.
--    (1) 0단계로 무엇이 지워질지 먼저 눈으로 확인
--    (2) mysqldump 로 전체 백업
--    (3) 그 다음에 1~2단계를 실행
--    (4) 3단계 DROP DATABASE 는 주석을 직접 풀어서 실행

-- ========== 0) 확인 (먼저 이것만 실행) ==========
-- 폐기 대상 DB 목록
SELECT schema_name AS '폐기 대상 DB'
  FROM information_schema.schemata
 WHERE schema_name = 'erp'
    OR schema_name LIKE 'erp_company_%'
 ORDER BY schema_name;

-- 인증 DB에 남아 있는 렌터카 계정 (target_db 가 loan_company_ 로 시작하지 않는 행)
SELECT id, login_id, company_name, target_db, is_active
  FROM login_users
 WHERE target_db NOT LIKE 'loan_company_%'
 ORDER BY id;

-- ========== 1) 렌터카 계정 비활성화 (되돌릴 수 있는 단계) ==========
-- 먼저 끄고 며칠 운영해 본 뒤 문제가 없으면 2단계로 넘어간다.
UPDATE login_users
   SET is_active = 0
 WHERE target_db NOT LIKE 'loan_company_%'
   AND role <> 'PLATFORM_ADMIN';

-- ========== 2) 렌터카 계정 행 삭제 ==========
-- 회사별 하위 계정(company_users)을 먼저 지우고 통합 계정(login_users)을 지운다.
DELETE cu FROM company_users cu
  JOIN login_users lu ON lu.id = cu.company_id
 WHERE lu.target_db NOT LIKE 'loan_company_%'
   AND lu.role <> 'PLATFORM_ADMIN';

DELETE FROM login_users
 WHERE target_db NOT LIKE 'loan_company_%'
   AND role <> 'PLATFORM_ADMIN';

-- ========== 3) 렌터카 DB 폐기 (되돌릴 수 없음 — 주석을 직접 풀 것) ==========
-- 0단계 목록과 백업을 확인한 뒤에만 실행한다.
--
-- DROP DATABASE `erp_company_jdend`;
-- DROP DATABASE `erp`;
--
-- 테넌트가 여러 개면 0단계 조회 결과를 보고 한 줄씩 추가한다.
-- (information_schema 로 DROP 문을 한꺼번에 만들려면:)
-- SELECT CONCAT('DROP DATABASE `', schema_name, '`;')
--   FROM information_schema.schemata
--  WHERE schema_name = 'erp' OR schema_name LIKE 'erp_company_%';
