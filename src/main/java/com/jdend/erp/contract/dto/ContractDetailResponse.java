package com.jdend.erp.contract.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 여신계약 상세 응답 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ContractDetailResponse {

  private Long id;
  private String contractNumber;

  private String customerNumber;
  private String customerName;
  private String customerPhone;
  private String customerAddress;
  private String customerRegistrationNumber;
  private String customerType;

  private String loanType;

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

  private String status;
  private Long remainingPrincipal;
  private String remarks;
}
