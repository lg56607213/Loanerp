package com.jdend.erp.borrowing.dto;

import lombok.*;

import java.time.LocalDate;

/** 상환·이자지급 등록 요청 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BorrowingTransactionRequest {

  public String borrowingNumber;
  public LocalDate txDate;

  public Long principalAmount;   // 상환원금 (이자만 낼 때는 0)
  public Long interestAmount;    // 지급이자 (원금만 갚을 때는 0)

  public LocalDate interestFrom; // 이자 계산 기간 (자동계산 값을 그대로 넘긴다)
  public LocalDate interestTo;

  public String withdrawAccount;
  public Integer installmentNo;  // 스케줄 회차. 수시상환이면 비운다.
  public String memo;

  public Boolean createVoucher;  // 기본 true
}
