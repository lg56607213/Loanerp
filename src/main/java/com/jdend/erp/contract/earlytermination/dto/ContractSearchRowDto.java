package com.jdend.erp.contract.earlytermination.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ContractSearchRowDto {
  private Long contractId;
  private String contractNumber;

  private String customerName;

  private String contractType;
  private LocalDate startDate;
  private LocalDate endDate;

  private Long monthlyRent;
  private Long totalRent;
}