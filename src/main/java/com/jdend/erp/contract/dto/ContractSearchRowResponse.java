package com.jdend.erp.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 채권 검색 행 (중도상환·만기관리 조회용) */
@Data
@Builder
@AllArgsConstructor
public class ContractSearchRowResponse {

  private String contractNumber;   // 채권번호
  private String customerName;     // 고객명
  private String loanType;         // 대출구분

  private LocalDate startDate;
  private LocalDate endDate;

  private Long loanAmount;         // 대출금
  private BigDecimal interestRate; // 약정 연이율
  private Long monthlyPayment;     // 월납입액
  private Long remainingPrincipal; // 잔여원금
}
