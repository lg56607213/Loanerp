package com.jdend.erp.accounting.partnerbalance.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerBalanceRequest {
  private String balanceType;     // 미수금 / 미지급금
  private String partnerName;
  private String partnerBizNo;
  private LocalDate occurredDate;
  private LocalDate dueDate;
  private Long amount;
  private Long settledAmount;
  private String source;
  private String item;
  private String remarks;
}
