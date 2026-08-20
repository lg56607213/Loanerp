package com.jdend.erp.dashboard.dto;

import lombok.*;

import java.util.List;

/**
 * 연체 경과기간(aging) 분포.
 *
 * 연체가 오래될수록 회수율이 급격히 떨어지므로 구간을 나눠 본다.
 * 90일 초과 구간이 커지면 기한이익상실·대손상각을 검토해야 한다는 신호다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class OverdueAgingResponse {

  private Integer totalCount;
  private Long totalAmount;

  private List<Bucket> buckets;

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor
  @Builder
  public static class Bucket {
    /** 구간 이름 (예: 31~60일) */
    private String label;
    /** 채권 건수 — 회차가 아니라 채권 기준 */
    private Integer count;
    /** 미납 합계 */
    private Long amount;
    /** 전체 연체금액 대비 비중(%) */
    private Double ratio;
  }
}
