package com.jdend.erp.payment.receivable.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ReceivableResponse {
  private Long id;

  private Long contractId;
  private String contractNumber;

  private String customerName;

  private Long receivableAmount;
  private LocalDate receivableDate;
  private String receivableType;
  private String content;
  private String status;
}