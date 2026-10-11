package com.jdend.erp.accounting.statements.dto;

import lombok.*;

import java.time.LocalDate;

/** 재무제표에 반영되지 않는 전표 라인 한 줄 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UnreflectedLineResponse {

  public Long voucherId;
  public String voucherNo;
  public LocalDate voucherDate;
  public String status;

  public String lineType;      // DEBIT / CREDIT
  public String accountCode;
  public String accountName;
  public Long amount;
  public String description;

  /** 왜 빠지는지 */
  public String reason;
}
