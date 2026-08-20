package com.jdend.erp.accounting.statements.dto;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BalanceDetailRowResponse {

  private LocalDate voucherDate;
  private String voucherNo;
  private String lineType;
  private String accountName;
  private Long amount;
  private String description;
  private String contractNumber;
  private String memo;
  private String status;
}