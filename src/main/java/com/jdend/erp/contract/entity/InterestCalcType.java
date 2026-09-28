package com.jdend.erp.contract.entity;

import java.util.Set;

/**
 * 정기 회차 이자 계산 방식.
 *
 * <ul>
 *   <li>월할 — 연이율 ÷ 12. 달의 길이와 무관하게 매회 같은 이자가 붙는다.</li>
 *   <li>일할 — 연이율 ÷ 365 × 해당 회차 일수. 31일인 달은 더, 2월은 덜 붙는다.</li>
 * </ul>
 *
 * 원리금균등은 "매회 납입액이 같다"가 정의라 일할과 양립하지 않는다.
 * 그래서 원리금균등에는 일할을 허용하지 않는다({@link #isAllowedFor}).
 *
 * 중도상환·기한이익상실 등 정산 시점의 일할은 이 설정과 무관하게
 * 언제나 DailyInterestCalculator 가 처리한다.
 */
public final class InterestCalcType {

  /** 월할 — 연이율 ÷ 12 */
  public static final String MONTHLY = "월할";
  /** 일할 — 연이율 ÷ 365 × 일수 */
  public static final String DAILY = "일할";

  public static final Set<String> ALL = Set.of(MONTHLY, DAILY);

  public static boolean isValid(String v) {
    return v != null && ALL.contains(v);
  }

  public static boolean isDaily(String v) {
    return DAILY.equals(v);
  }

  /** 값이 없으면 기존 계약과 같게 '월할'로 본다. 기존 금액이 바뀌지 않게 하려는 것이다. */
  public static String orDefault(String v) {
    return isValid(v) ? v : MONTHLY;
  }

  /** 원리금균등은 납입액 고정이 정의이므로 일할을 쓸 수 없다. */
  public static boolean isAllowedFor(String repaymentMethod, String interestCalcType) {
    if (!isDaily(interestCalcType)) return true;
    return !RepaymentMethod.EQUAL_PAYMENT.equals(repaymentMethod);
  }

  private InterestCalcType() {}
}
