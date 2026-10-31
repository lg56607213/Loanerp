package com.jdend.erp.accounting.partnerbalance.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerBalanceRowResponse {
  private Long id;
  private String balanceType;
  private String partnerName;
  private String partnerBizNo;
  private LocalDate occurredDate;
  private LocalDate dueDate;
  private Long amount;
  private Long settledAmount;
  private Long balance;
  private String status;
  /** 발생일로부터 오늘까지 — 미결·부분일 때만 센다 */
  private Integer agingDays;
  private String source;
  private String item;
  private String remarks;
}
