package com.jdend.erp.contract.support;

import java.math.BigDecimal;

/**
 * 대부업법상 이율 상한 검증.
 *
 * 화면에서만 막으면 API 직접 호출로 우회되므로 서비스 레이어에서 반드시 호출한다.
 */
public final class LoanRateValidator {

  /** 대부업법 최고이자율(연 %). 법 개정 시 이 값만 바꾼다. */
  public static final BigDecimal MAX_RATE = new BigDecimal("20.00");

  /** 연체이율은 약정이율 + 이 값을 초과할 수 없다. */
  public static final BigDecimal OVERDUE_SPREAD_CAP = new BigDecimal("3.00");

  private static final BigDecimal ZERO = BigDecimal.ZERO;

  /**
   * 약정이율 검증.
   * @throws IllegalArgumentException 0 미만이거나 최고이자율 초과 시
   */
  public static void validateInterestRate(BigDecimal rate) {
    if (rate == null) throw new IllegalArgumentException("이자율은 필수입니다.");
    if (rate.compareTo(ZERO) < 0) {
      throw new IllegalArgumentException("이자율은 0% 미만일 수 없습니다.");
    }
    if (rate.compareTo(MAX_RATE) > 0) {
      throw new IllegalArgumentException(
          "이자율이 법정 최고이자율(연 " + MAX_RATE.stripTrailingZeros().toPlainString() + "%)을 초과합니다. 입력값: " + rate + "%");
    }
  }

  /**
   * 연체이율 검증. 약정이율 + 3%p 와 최고이자율 중 낮은 값이 상한이다.
   * @param overdueRate  연체이율 (null 이면 검증 생략 — 연체이자 미부과 계약)
   * @param interestRate 약정이율
   */
  public static void validateOverdueRate(BigDecimal overdueRate, BigDecimal interestRate) {
    if (overdueRate == null) return;
    if (overdueRate.compareTo(ZERO) < 0) {
      throw new IllegalArgumentException("연체이율은 0% 미만일 수 없습니다.");
    }
    BigDecimal cap = maxOverdueRate(interestRate);
    if (overdueRate.compareTo(cap) > 0) {
      throw new IllegalArgumentException(
          "연체이율이 상한(" + cap.stripTrailingZeros().toPlainString() + "%)을 초과합니다. "
              + "상한은 약정이율 + 3%p 와 법정 최고이자율 중 낮은 값입니다. 입력값: " + overdueRate + "%");
    }
  }

  /** 해당 약정이율에서 허용되는 연체이율 상한 */
  public static BigDecimal maxOverdueRate(BigDecimal interestRate) {
    if (interestRate == null) return MAX_RATE;
    BigDecimal spread = interestRate.add(OVERDUE_SPREAD_CAP);
    return spread.compareTo(MAX_RATE) > 0 ? MAX_RATE : spread;
  }

  private LoanRateValidator() {}
}
