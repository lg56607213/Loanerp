package com.jdend.erp.accounting.monthlyvoucher.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class MonthlyVoucherRuleCreateResponse {
  private Long id;
  private Boolean isActive;

  private String contractNumber;
  private Integer monthlyDay;

  private LocalDate nextRunDate;

  private String debitAccount;
  private Long debitAmount;

  private String creditAccount;
  private Long creditAmount;
}