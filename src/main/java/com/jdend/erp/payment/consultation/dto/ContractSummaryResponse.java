package com.jdend.erp.payment.consultation.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ContractSummaryResponse {
  private String contractNumber;
  private String customerName;
}