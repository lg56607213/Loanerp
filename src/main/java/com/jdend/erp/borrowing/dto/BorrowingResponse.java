package com.jdend.erp.borrowing.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 차입금 한 건 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BorrowingResponse {

  public Long id;
  public String borrowingNumber;

  public String lenderName;
  public String borrowingType;
  public String lenderContact;

  public Long principalAmount;
  public LocalDate borrowDate;
  public LocalDate maturityDate;

  public BigDecimal interestRate;

  public String repaymentMethod;
  public Integer installmentCount;
  public Integer paymentDay;
  public String paymentDayType;

  public String depositAccount;
  public String status;
  public Long remainingPrincipal;
  public Long voucherId;
  public String remarks;

  /** 지금까지 갚은 원금 */
  public Long repaidPrincipal;
  /** 지금까지 낸 이자 */
  public Long paidInterest;
  /** 상환·이자지급 건수 */
  public Integer transactionCount;
}
