package com.jdend.erp.payment.payment.support;

import java.util.Set;

/**
 * 수납받은 돈이 어디서 왔는지.
 *
 * 선수금으로 수납하면 새로 들어온 현금이 없다. 이미 받아 둔 돈을 쓰는 것이라
 * 전표 차변이 보통예금이 아니라 선수금(부채 감소)이 되어야 한다.
 * 이걸 구분하지 않으면 같은 돈을 두 번 받은 것처럼 예금이 부풀어 오른다.
 */
public final class PaymentSource {

  /** 기본값 — 통장으로 새로 들어온 돈 */
  public static final String BANK = "보통예금";
  /** 미리 받아 둔 선수금에서 차감 */
  public static final String PREPAID = "선수금";

  public static final Set<String> ALL = Set.of(BANK, PREPAID);

  public static boolean isPrepaid(String v) {
    return PREPAID.equals(v);
  }

  /** 값이 없으면 기존 수납과 같게 보통예금으로 본다. */
  public static String orDefault(String v) {
    return (v != null && ALL.contains(v.trim())) ? v.trim() : BANK;
  }

  private PaymentSource() {}
}
