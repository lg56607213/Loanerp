package com.jdend.erp.borrowing.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 차입금 등록·수정 요청 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BorrowingRequest {

  public String lenderName;        // 차입처
  public String borrowingType;     // 금융기관 / 개인 / 법인 / 관계사
  public String lenderContact;

  public Long principalAmount;     // 차입원금
  public LocalDate borrowDate;     // 차입일
  public LocalDate maturityDate;   // 만기일

  public BigDecimal interestRate;  // 연이율(%)

  public String repaymentMethod;   // 만기일시 / 원금균등 / 원리금균등 / 수시상환
  public Integer installmentCount;
  public Integer paymentDay;
  public String paymentDayType;    // 일자 / 말일

  public String depositAccount;    // 입금받은 당사 계좌
  public String remarks;

  /** 차입 개시 전표를 만들지 여부. 기본 true. */
  public Boolean createVoucher;
}
