package com.jdend.erp.contract.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 여신계약 + 고객정보 전체 응답 (계약 상세화면용) */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ContractFullResponse {

  public Long id;
  public String contractNumber;

  public String customerNumber;
  public String customerName;
  public String customerPhone;
  public String customerAddress;
  public String customerRegistrationNumber;
  public String customerType;

  public String loanType;

  public Long loanAmount;
  public LocalDate executeDate;

  public BigDecimal interestRate;
  public BigDecimal overdueRate;
  public Boolean overdueChargeYn;

  public String repaymentMethod;

  public LocalDate startDate;
  public LocalDate endDate;
  public Integer paymentDay;
  public Integer installmentCount;
  public Long monthlyPayment;

  public String status;
  public Long remainingPrincipal;
  public String remarks;
}
