package com.jdend.erp.contract.maturitytermination.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class MaturityTerminationRowDto {
  private Long id;

  private String contractNumber;
  private String customerName;

  private String terminationMethod;
  private LocalDate terminationDate;

  private Long unpaidAmount;
  private String status;
}