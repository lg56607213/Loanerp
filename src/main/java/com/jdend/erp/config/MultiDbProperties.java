package com.jdend.erp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.multidb")
public class MultiDbProperties {

    private String defaultDb = "auth";

    // 대부업 ERP 전용 템플릿 DB. 다른 서비스와 같은 MySQL 인스턴스를 쓰더라도
    // 스키마가 섞이지 않도록 이름을 분리한다.
    private String templateDb = "loan_erp";

    private Map<String, DbInfo> datasources = new HashMap<>();

    @Getter
    @Setter
    public static class DbInfo {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
    }
}