package com.jdend.erp.borrowing.dto;

import lombok.*;

import java.time.LocalDate;

/** 상환·이자지급 한 건 (잔액은 날짜순으로 흘려 계산한 값) */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BorrowingTransactionResponse {

  public Long id;
  public String borrowingNumber;
  public LocalDate txDate;

  public Long principalAmount;
  public Long interestAmount;
  public Long totalAmount;

  public LocalDate interestFrom;
  public LocalDate interestTo;

  public String withdrawAccount;
  public Integer installmentNo;
  public String memo;
  public Long voucherId;

  /** 이 건까지 반영한 뒤의 미상환 원금 */
  public Long balanceAfter;
}
