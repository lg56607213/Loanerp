package com.jdend.erp.contract.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 채권현황 행 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractStatusRowResponse {

  private String contractNumber;
  private String customerName;
  private String loanType;

  /** contracts.status — 저장 상태 (해지/상각/종료) */
  private String status;
  /** 정상/연체/해지/상각/종료 — 저장 상태 + 미납 스케줄로 판정한 최종 상태 */
  private String contractStatus;

  private LocalDate contractStart;
  private LocalDate contractEnd;

  private Long loanAmount;
  private BigDecimal interestRate;
  private Long monthlyPayment;
  private Long remainingPrincipal;

  /** 미수 잔액 */
  private Long receivable;
}
