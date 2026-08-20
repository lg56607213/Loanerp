package com.jdend.erp.management.financial.dto;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinancialStatementVoucherRowResponse {

  private Long voucherId;
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