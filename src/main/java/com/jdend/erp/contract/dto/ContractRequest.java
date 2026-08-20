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

  public Long loanAmount;         // 대출금(원금)
  public LocalDate executeDate;   // 실행일

  public BigDecimal interestRate; // 약정 연이율(%)
  public BigDecimal overdueRate;  // 연체이율(%)
  public Boolean overdueChargeYn; // 연체이자 부과 여부

  public String repaymentMethod;  // 원리금균등 / 원금균등 / 만기일시

  public LocalDate startDate;
  public LocalDate endDate;
  public Integer paymentDay;      // 납입일자(1~31)
  public Integer installmentCount;
  public Long monthlyPayment;     // 미입력 시 상환방식에 따라 자동 산출

  public String status;
  public String remarks;
}
