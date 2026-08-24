package com.jdend.erp.auth.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanyApplicationRequest {
    private String companyName;
    private String representativeName;
    private String phone;
    private String email;
    private String loanBalanceScale;
    private String inquiry;
}
