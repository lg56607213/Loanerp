package com.jdend.erp.contract.maturity.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class MaturityDetailResponse {
  private Long id;

  private Long oldContractId;
  private String oldContractNumber;

  private String customerName;
  private LocalDate oldEndDate;

  private String newContractNumber;
  private LocalDate newStartDate;
  private LocalDate newEndDate;
  private Long newMonthlyRent;

  private String status;
}