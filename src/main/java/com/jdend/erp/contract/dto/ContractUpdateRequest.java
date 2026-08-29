package com.jdend.erp.contract.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 여신계약 수정 요청 — null 인 항목은 변경하지 않는다. */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ContractUpdateRequest {

  private String customerNumber;
  private String customerType;
  private String loanType;
  private String debtType;      // 개인금융채권 / 기타

  private Long loanAmount;
  private LocalDate executeDate;

  private BigDecimal interestRate;
  private BigDecimal overdueRate;
  private Boolean overdueChargeYn;

  private String repaymentMethod;

  private LocalDate startDate;
  private LocalDate endDate;
  private Integer paymentDay;
  private Integer installmentCount;
  private Long monthlyPayment;

  private String remarks;
}
