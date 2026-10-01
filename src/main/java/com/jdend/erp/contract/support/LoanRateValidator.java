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

  /**
   * 실효 연이율 검증 — 단수 처리까지 반영한 '실제로 받는' 이율.
   *
   * 명목이율이 상한 안이어도 회차 이자를 절상하면 실제로 받는 이자가 상한을 넘을 수 있다.
   * 2,000만원 연 20% 월할의 월 이자는 333,333.33원인데 백원단위 절상은 333,400원이 되어
   * 연 20.004%가 된다. 명목이율만 검사하면 이 건이 그대로 통과한다.
   *
   * @param effectiveRate 스케줄에 매겨진 이자로 환산한 연이율(%). null 이면 검증 생략.
   */
  public static void validateEffectiveRate(BigDecimal effectiveRate) {
    if (effectiveRate == null) return;
    if (effectiveRate.compareTo(MAX_RATE) <= 0) return;

    throw new IllegalArgumentException(
        "대부업법상 연이자율 초과입니다."
      + " 단수 처리까지 반영한 실효 연이율이 "
      + effectiveRate.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
      + "% 로 법정 최고이자율(연 " + MAX_RATE.stripTrailingZeros().toPlainString() + "%)을 넘습니다."
      + " 단수 처리를 '절사'나 '반올림'으로 바꾸거나 약정이율을 낮추세요.");
  }

  /** 해당 약정이율에서 허용되는 연체이율 상한 */
  public static BigDecimal maxOverdueRate(BigDecimal interestRate) {
    if (interestRate == null) return MAX_RATE;
    BigDecimal spread = interestRate.add(OVERDUE_SPREAD_CAP);
    return spread.compareTo(MAX_RATE) > 0 ? MAX_RATE : spread;
  }

  private LoanRateValidator() {}
}
