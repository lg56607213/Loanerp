package com.jdend.erp.loan.dto;

import lombok.*;

import java.time.LocalDate;

/**
 * 정산 명세 — 중도상환·기한이익상실·완제 시점의 청구 총액을 항목별로 나눈 결과.
 *
 * 정기 회차는 월할로 산출하지만, 정산 시점의 경과이자와 지연배상금은 일할(365일)로 계산한다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoanSettlementResponse {

  private String contractNumber;
  private String customerName;
  private LocalDate settlementDate;

  /** 정산일 기준 잔여원금 */
  private Long remainingPrincipal;

  /** 미납 회차에 남아 있는 약정이자 */
  private Long unpaidInterest;

  /** 직전 납입일 다음날 ~ 정산일까지 일할로 붙은 경과이자 */
  private Long accruedInterest;

  /** 기산일 (직전 납입예정일) */
  private LocalDate accrualFrom;

  /** 경과일수 */
  private Integer accrualDays;

  /** 지연배상금 — 연체이자 미부과 채권은 0 */
  private Long overdueInterest;

  /** 연체이자 부과 여부 */
  private Boolean overdueCharged;

  /** 미회수 법적비용 */
  private Long legalCost;

  /** 청구 총액 = 잔여원금 + 미납이자 + 경과이자 + 지연배상금 + 법적비용 */
  private Long totalDue;

  /** 계산 근거 설명 — 화면에 그대로 노출한다 */
  private String note;
}
