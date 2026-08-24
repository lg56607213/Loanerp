package com.jdend.erp.auth.dto;

import com.jdend.erp.auth.entity.CompanyApplication;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class CompanyApplicationResponse {

    private final Long id;
    private final String companyName;
    private final String representativeName;
    private final String phone;
    private final String email;
    private final String loanBalanceScale;
    private final String inquiry;
    private final String status;
    private final LocalDateTime createdAt;

    public CompanyApplicationResponse(CompanyApplication a) {
        this.id = a.getId();
        this.companyName = a.getCompanyName();
        this.representativeName = a.getRepresentativeName();
        this.phone = a.getPhone();
        this.email = a.getEmail();
        this.loanBalanceScale = a.getLoanBalanceScale();
        this.inquiry = a.getInquiry();
        this.status = a.getStatus();
        this.createdAt = a.getCreatedAt();
    }
}
