package com.jdend.erp.contract.entity;

import java.util.Set;

/**
 * 납입일자 지정 방식.
 *
 * 달마다 말일이 28·29·30·31일로 달라지므로, "매월 말일"을 숫자 하나로는 적을 수 없다.
 * 숫자로 지정하는 방식과 말일로 지정하는 방식을 나눈다.
 */
public final class PaymentDayType {

  /** 숫자로 지정 — payment_day 에 적힌 날. 그 달에 없는 날이면 말일로 내린다(2월 31일 → 28일) */
  public static final String DAY = "일자";
  /** 매월 말일 — payment_day 는 쓰지 않는다 */
  public static final String LAST_DAY = "말일";

  public static final Set<String> ALL = Set.of(DAY, LAST_DAY);

  public static boolean isValid(String v) {
    return v != null && ALL.contains(v);
  }

  public static boolean isLastDay(String v) {
    return LAST_DAY.equals(v);
  }

  /** 값이 없으면 기존 계약과 같게 '일자'로 본다. */
  public static String orDefault(String v) {
    return isValid(v) ? v : DAY;
  }

  private PaymentDayType() {}
}
