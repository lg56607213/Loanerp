package com.jdend.erp.contract.dto;

import lombok.*;

/** 채권 요약 (청구서 발행 대상 목록 등) */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractSummaryResponse {
  private String contractNumber;

  private String customerName;
  private String registrationNumber;
  private String email;

  private Long monthlyPayment;
  private String contractStatus; // contracts.status
}
