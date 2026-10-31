package com.jdend.erp.accounting.partnerbalance.dto;

import lombok.*;

import java.util.List;

/** 거래처별 잔액 요약 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerBalanceSummaryResponse {
  private long receivableTotal;
  private long payableTotal;
  private List<Row> rows;

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor @Builder
  public static class Row {
    private String balanceType;
    private String partnerName;
    private int count;
    private long amount;
    private long settledAmount;
    private long balance;
    /** 가장 오래된 미결 건의 경과일 */
    private Integer oldestAgingDays;
  }
}
