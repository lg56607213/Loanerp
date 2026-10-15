package com.jdend.erp.contract.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 여신계약 등록 요청 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ContractRequest {

  public String customerNumber;   // 고객번호(C001)
  public String customerType;     // 개인 / 법인
  public String loanType;         // 신용대출 / 담보대출 / 사업자대출
  public String debtType;         // 개인금융채권 / 기타 (미입력 시 자동 판정)

  public Long loanAmount;         // 대출금(원금)
  public LocalDate executeDate;   // 실행일

  public BigDecimal interestRate; // 약정 연이율(%)
  public BigDecimal overdueRate;  // 연체이율(%)
  public Boolean overdueChargeYn; // 연체이자 부과 여부

  public String repaymentMethod;  // 원리금균등 / 원금균등 / 만기일시

  public LocalDate startDate;
  public LocalDate endDate;
  public Integer paymentDay;      // 납입일자(1~31)
  public String paymentDayType;   // 일자 / 말일
  public String interestCalcType; // 월할 / 일할

  public String executeAccount;   // 대출금을 내준 당사 계좌
  public Integer roundingUnit;    // 납입금액 단수 단위 1 / 10 / 100
  public String roundingMode;     // 절사 / 절상 / 반올림
  public Integer installmentCount;

  /**
   * 대출 실행 전표를 만들지 여부. 비우면 만든다(지금까지와 같다).
   *
   * 그 시기 전표를 이미 따로 올려 둔 뒤 대출을 옮겨 적을 때 'N' 으로 둔다.
   * 그러지 않으면 같은 실행 건이 전표로 두 번 적힌다.
   */
  public Boolean createVoucher;
  public Long monthlyPayment;     // 미입력 시 상환방식에 따라 자동 산출

  public String status;
  public String remarks;
}
