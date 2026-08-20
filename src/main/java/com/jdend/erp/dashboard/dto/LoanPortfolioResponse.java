package com.jdend.erp.dashboard.dto;

import lombok.*;

import java.util.List;

/**
 * 여신 포트폴리오 요약 — 대시보드 상단 지표.
 *
 * 채권 상태 5종(정상/연체/해지/상각/종료)별 건수와 잔액을 집계한다.
 * 정상·연체는 저장값이 아니라 미납 스케줄로 판정한 파생 상태를 쓴다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LoanPortfolioResponse {

  /** 상각·종료를 제외한 살아 있는 채권 건수 */
  private Integer activeCount;
  /** 살아 있는 채권의 잔여원금 합계 */
  private Long outstandingPrincipal;

  /** 연체 채권 건수 / 잔여원금 */
  private Integer overdueCount;
  private Long overduePrincipal;

  /** 연체율 = 연체 잔여원금 / 살아 있는 잔여원금 (%) */
  private Double overdueRatio;

  /** 상각 채권 건수 / 상각 잔액 */
  private Integer writtenOffCount;
  private Long writtenOffAmount;

  /** 상태별 상세 */
  private List<StatusRow> byStatus;

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor
  @Builder
  public static class StatusRow {
    private String status;
    private Integer count;
    private Long principal;
  }
}
