package com.jdend.erp.accounting.monthlyvoucher.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class MonthlyVoucherRuleCreateRequest {

  private String contractNumber;       // 선택

  private Integer monthlyDate;         // 필수(1~31)

  private String debitAccountCode;     // 계정코드 (우선)
  private String debitAccount;         // 필수
  private Long debitAmount;            // 필수
  private String debitDescription;     // 선택

  private String creditAccountCode;    // 계정코드 (우선)
  private String creditAccount;        // 필수
  private Long creditAmount;           // 필수
  private String creditDescription;    // 선택

  private String memo;                 // 선택
}