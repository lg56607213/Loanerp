package com.jdend.erp.contract.entity;

import java.util.Set;

/** 상환방식 — 계약 등록 시 건별로 선택한다. */
public final class RepaymentMethod {

  /** 원리금균등상환 — 매회 납입액 동일. PMT 공식으로 산출 */
  public static final String EQUAL_PAYMENT = "원리금균등";
  /** 원금균등상환 — 매회 원금 동일, 이자는 잔액 기준 체감 */
  public static final String EQUAL_PRINCIPAL = "원금균등";
  /** 만기일시상환 — 매월 이자만 납입하고 만기에 원금 전액 */
  public static final String BULLET = "만기일시";

  public static final Set<String> ALL = Set.of(EQUAL_PAYMENT, EQUAL_PRINCIPAL, BULLET);

  public static boolean isValid(String v) {
    return v != null && ALL.contains(v);
  }

  private RepaymentMethod() {}
}
