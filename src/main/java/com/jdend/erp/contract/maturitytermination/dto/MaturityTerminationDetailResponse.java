package com.jdend.erp.contract.maturitytermination.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class MaturityTerminationDetailResponse {
  private Long id;

  private Long contractId;
  private String contractNumber;

  private String customerName;
  private String contractType;

  private LocalDate startDate;
  private LocalDate endDate;
  private Long monthlyRent;

  private String terminationMethod;
  private LocalDate terminationDate;
  private Long unpaidAmount;
  private String status;
}