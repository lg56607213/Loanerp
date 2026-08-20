package com.jdend.erp.contract.maturity.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class MaturityRowDto {
  private Long id;

  private String oldContractNumber;
  private String newContractNumber;

  private String customerName;

  private LocalDate oldEndDate;
  private LocalDate newStartDate;
  private LocalDate newEndDate;

  private Long newMonthlyRent;
  private String status;
}