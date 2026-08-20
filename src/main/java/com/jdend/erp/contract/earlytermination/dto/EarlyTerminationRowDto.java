package com.jdend.erp.contract.earlytermination.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class EarlyTerminationRowDto {
  private Long id;
  private String contractNumber;
  private String customerName;

  private String terminationMethod;
  private LocalDate terminationDate;

  private Long terminationAmount;
  private Long uncollectedRent;
  private Long totalAmount;

  private String status;
}