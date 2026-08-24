package com.jdend.erp.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TenantDatabaseService {

    private final JdbcTemplate jdbcTemplate;
    private final DynamicRoutingDataSource routingDataSource;
    private final MultiDbProperties properties;

    public String normalizeTenantDb(String input, String loginId) {
        String raw = input == null || input.isBlank() ? loginId : input;

        String cleaned = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_");

        if (cleaned.isBlank()) {
            throw new RuntimeException("회사코드/DB 값이 올바르지 않습니다.");
        }

        if (cleaned.startsWith("loan_company_")) {
            return cleaned;
        }

        return "loan_company_" + cleaned;
    }

    public void ensureTenantDatabase(String targetDb) {
        validateDbName(targetDb);
        createDatabaseIfNotExists(targetDb);
        copySchemaIfEmpty(targetDb);
        ensureFinancialStatementAccounts(targetDb);
        ensureDefaultOtherAccountSettings(targetDb);
        registerDataSourceIfMissing(targetDb);
    }

    private void createDatabaseIfNotExists(String targetDb) {
        jdbcTemplate.execute(
                "CREATE DATABASE IF NOT EXISTS `" + targetDb + "` " +
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        );
    }

    private void copySchemaIfEmpty(String targetDb) {
        String templateDb = properties.getTemplateDb();

        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                """,
                Integer.class,
                targetDb
        );

        if (tableCount != null && tableCount > 0) {
            return;
        }

        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """,
                String.class,
                templateDb
        );

        for (String table : tables) {
            if ("login_users".equalsIgnoreCase(table) || "company_users".equalsIgnoreCase(table)) {
                continue;
            }

            jdbcTemplate.execute(
                    "CREATE TABLE `" + targetDb + "`.`" + table + "` " +
                    "LIKE `" + templateDb + "`.`" + table + "`"
            );
        }

    }

    // 재무제표 계정이 비어있는 테넌트(기존 업체 포함)에 기본 계정 트리를 채워준다.
    // copySchemaIfEmpty와 독립적으로 동작하여 테이블이 이미 있어도 계정이 0건이면 복제한다.
    private void ensureFinancialStatementAccounts(String targetDb) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `" + targetDb + "`.`financial_statement_accounts`",
                Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        seedDefaultFinancialStatementAccounts(targetDb, properties.getTemplateDb());
    }

    // id를 그대로 복제해야 parent_id 트리 연결이 깨지지 않는다.
    private void seedDefaultFinancialStatementAccounts(String targetDb, String templateDb) {
        Integer templateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `" + templateDb + "`.`financial_statement_accounts`",
                Integer.class
        );
        if (templateCount == null || templateCount == 0) {
            return;
        }

        jdbcTemplate.update(
                "INSERT INTO `" + targetDb + "`.`financial_statement_accounts` " +
                "(id, statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable) " +
                "SELECT id, statement_type, category, level, parent_id, account_code, account_name, account_type, display_order, is_active, is_postable " +
                "FROM `" + templateDb + "`.`financial_statement_accounts` " +
                "ORDER BY level ASC, id ASC"
        );

        Long maxId = jdbcTemplate.queryForObject(
                "SELECT IFNULL(MAX(id), 0) FROM `" + targetDb + "`.`financial_statement_accounts`",
                Long.class
        );

        jdbcTemplate.execute(
                "ALTER TABLE `" + targetDb + "`.`financial_statement_accounts` AUTO_INCREMENT = " + (maxId + 1)
        );
    }

    private void registerDataSourceIfMissing(String targetDb) {
        if (routingDataSource.containsDataSource(targetDb)) {
            return;
        }

        MultiDbProperties.DbInfo auth = properties.getDatasources().get(properties.getDefaultDb());

        if (auth == null) {
            throw new RuntimeException("기본 DB 설정(auth)을 찾을 수 없습니다.");
        }

        String tenantUrl = replaceDbName(auth.getUrl(), targetDb);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(tenantUrl);
        ds.setUsername(auth.getUsername());
        ds.setPassword(auth.getPassword());
        ds.setDriverClassName(auth.getDriverClassName());

        routingDataSource.addDataSource(targetDb, ds);
    }

    private String replaceDbName(String url, String targetDb) {
        return url.replaceFirst(
                "(jdbc:mysql://[^/]+/)([^?]+)(.*)",
                "$1" + targetDb + "$3"
        );
    }

    /**
     * 신규 테넌트에 기타계정관리 기본 설정이 없으면 대부업 표준 계정 매핑을 자동으로 삽입한다.
     * 이미 설정이 있으면 (count > 0) 건너뜀.
     */
    private void ensureDefaultOtherAccountSettings(String targetDb) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + targetDb + "`.`accounting_other_account_settings`",
                    Integer.class
            );
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.update(
                    "INSERT INTO `" + targetDb + "`.`accounting_other_account_settings` (settings_json) VALUES (?)",
                    buildDefaultSettingsJson()
            );
        } catch (Exception e) {
            // 테이블이 아직 없는 경우(스키마 복제 전) 등 예외는 무시
        }
    }

    private String buildDefaultSettingsJson() {
        return """
{
  "loanOpenMapping": {
    "debit":  {"account": "100101", "accountName": "보통예금"},
    "credit": {"account": "200201", "accountName": "차입금"}
  },
  "loanMapping": {
    "debit1": {"account": "200201", "accountName": "차입금"},
    "debit2": {"account": "500101", "accountName": "이자비용"},
    "credit": {"account": "100101", "accountName": "보통예금"}
  },
  "paymentMapping": {
    "debit":     {"account": "100101", "accountName": "보통예금"},
    "credit":    {"account": "400101", "accountName": "이자수익"}
  },
  "prepaidMapping": {
    "bank":      {"account": "100101", "accountName": "보통예금"},
    "debit":     {"account": "200102", "accountName": "선수금"},
    "credit":    {"account": "400101", "accountName": "이자수익"}
  },
  "legalCostMapping": {
    "debit":  {"account": "500102", "accountName": "법무비용"},
    "credit": {"account": "200101", "accountName": "미지급금"}
  },
  "earlyTermMapping": {
    "unrealizedRent": {
      "debit":     {"account": "100503", "accountName": "미수금"},
      "credit":    {"account": "400101", "accountName": "이자수익"}
    },
    "terminationFee": {
      "debit":  {"account": "100101", "accountName": "보통예금"},
      "credit": {"account": "400104", "accountName": "중도상환수수료수익"}
    },
    "terminationAmount": {
      "debit":  {"account": "100101", "accountName": "보통예금"},
      "credit": {"account": "100503", "accountName": "미수금"}
    }
  }
}""";
    }

    private void validateDbName(String dbName) {
        if (dbName == null || !dbName.matches("^[a-zA-Z0-9_]+$")) {
            throw new RuntimeException("DB 이름은 영문/숫자/언더바만 가능합니다.");
        }
    }
}