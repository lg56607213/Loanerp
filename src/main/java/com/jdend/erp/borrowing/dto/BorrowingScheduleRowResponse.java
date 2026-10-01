package com.jdend.erp.borrowing.dto;

import lombok.*;

import java.time.LocalDate;

/** 약정 상환스케줄 한 회차 + 그 회차에 실제로 낸 금액 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BorrowingScheduleRowResponse {

  public Integer installmentNo;
  public LocalDate dueDate;

  public Long principalAmount;   // 약정 원금
  public Long interestAmount;    // 약정 이자
  public Long totalAmount;       // 약정 합계
  public Long balance;           // 약정상 잔액

  public Long paidAmount;        // 이 회차로 등록된 실제 지급액
  public Long unpaidAmount;      // 약정 대비 미납
  public String lineStatus;      // 완납 / 부분지급 / 미지급 / 예정
}
