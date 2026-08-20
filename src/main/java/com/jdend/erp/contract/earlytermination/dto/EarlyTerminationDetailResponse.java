package com.jdend.erp.contract.earlytermination.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class EarlyTerminationDetailResponse {
  private Long id;

  private Long contractId;
  private String contractNumber;

  private String customerName;
  private String contractType;

  private LocalDate startDate;
  private LocalDate endDate;

  private Long monthlyRent;
  private Long totalRent;

  private String terminationMethod;
  private LocalDate terminationDate;
  private String status;

  private Long terminationAmount;
  private Long uncollectedRent;
  private Long terminationFee;
  private Long totalAmount;
}