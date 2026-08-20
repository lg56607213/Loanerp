package com.jdend.erp.contract.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 여신계약 기본 응답 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ContractResponse {

  public Long id;
  public String contractNumber;

  public String customerNumber;
  public String customerName;
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
