package com.jdend.erp.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 앱 시작 시 모든 테넌트 DB(loan_company_*)에 Hibernate ddl-auto=update를 적용한다.
 * 멀티테넌트 구조에서 기본 DataSource(erp)에만 적용되는 ddl-auto=update 한계를 보완한다.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class TenantSchemaUpdateRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final MultiDbProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        List<String> tenantDbs = jdbcTemplate.queryForList(
            "SELECT schema_name FROM information_schema.schemata " +
            "WHERE schema_name LIKE 'loan_company_%' ORDER BY schema_name",
            String.class
        );

        if (tenantDbs.isEmpty()) {
            log.info("[TenantSchemaUpdate] 테넌트 DB 없음, 스킵");
            return;
        }

        MultiDbProperties.DbInfo auth = properties.getDatasources().get(properties.getDefaultDb());
        if (auth == null) {
            log.warn("[TenantSchemaUpdate] auth DB 설정 없음, 스킵");
            return;
        }

        log.info("[TenantSchemaUpdate] {}개 테넌트 DB 스키마 업데이트 시작", tenantDbs.size());

        int success = 0;
        int fail = 0;
        for (String dbName : tenantDbs) {
            try {
                updateSchema(dbName, auth);
                log.info("[TenantSchemaUpdate] {} 완료", dbName);
                success++;
            } catch (Exception e) {
                log.error("[TenantSchemaUpdate] {} 실패: {}", dbName, e.getMessage());
                fail++;
            }
        }

        log.info("[TenantSchemaUpdate] 전체 완료 — 성공: {}, 실패: {}", success, fail);
    }

    private void updateSchema(String dbName, MultiDbProperties.DbInfo auth) {
        String tenantUrl = auth.getUrl().replaceFirst(
            "(jdbc:mysql://[^/]+/)([^?]+)(.*)",
            "$1" + dbName + "$3"
        );

        HikariDataSource ds = null;
        try {
            ds = new HikariDataSource();
            ds.setJdbcUrl(tenantUrl);
            ds.setUsername(auth.getUsername());
            ds.setPassword(auth.getPassword());
            ds.setDriverClassName(auth.getDriverClassName());
            ds.setMaximumPoolSize(2);
            ds.setConnectionTimeout(10_000);
            ds.setPoolName("schema-upd-" + dbName);

            Map<String, Object> props = new HashMap<>();
            props.put("hibernate.hbm2ddl.auto", "update");
            props.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            props.put("hibernate.implicit_naming_strategy",
                "org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl");
            props.put("hibernate.show_sql", "false");

            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(ds);
            emf.setPackagesToScan("com.jdend.erp");
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(props);
            emf.afterPropertiesSet();

            if (emf.getObject() != null) {
                emf.getObject().close();
            }
        } finally {
            if (ds != null) {
                ds.close();
            }
        }
    }
}
