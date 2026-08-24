package com.jdend.erp.contract.maturity.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class MaturityCreateRequest {

  // 기존 계약번호(돋보기로 선택)
  private String oldContractNumber;

  // 신규 계약(재약정)
  private LocalDate newStartDate;
  private LocalDate newEndDate;
  private Long newMonthlyRent;

  // 만기예정/재계약완료
  private String status;
}