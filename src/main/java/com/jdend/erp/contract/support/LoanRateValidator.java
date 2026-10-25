package com.jdend.erp.contract.support;

import java.math.BigDecimal;

/**
 * 이율 상한 검증.
 *
 * 화면에서만 막으면 API 직접 호출로 우회되므로 서비스 레이어에서 반드시 호출한다.
 *
 * <p>상한은 회사가 정한다({@code LoanPolicyService}). 대부업 장부는 법정 상한
 * 연 20% 를 넘길 수 없지만, 비영업으로 개인이 빌려준 돈을 적는 장부도 같은 ERP 를
 * 쓴다. 상한을 코드에 박아 두면 그런 장부에 실제 약정을 적을 수 없다.
 * 상한을 넘기지 않는 한 결과는 지금까지와 같다.
 */
public final class LoanRateValidator {

  /** 법정 최고이자율(연 %). 법 개정 시 이 값만 바꾼다. */
  public static final BigDecimal LEGAL_MAX_RATE = new BigDecimal("20.00");

  /** 예전 이름 — 상한을 넘겨 주지 않는 호출이 쓴다. */
  public static final BigDecimal MAX_RATE = LEGAL_MAX_RATE;

  /** 연체이율은 약정이율 + 이 값을 초과할 수 없다. */
  public static final BigDecimal OVERDUE_SPREAD_CAP = new BigDecimal("3.00");

  private static final BigDecimal ZERO = BigDecimal.ZERO;

  /**
   * 약정이율 검증.
   * @throws IllegalArgumentException 0 미만이거나 최고이자율 초과 시
   */
  public static void validateInterestRate(BigDecimal rate) {
    validateInterestRate(rate, LEGAL_MAX_RATE);
  }

  /** 약정이율 검증 — 상한을 회사 설정으로 받는다. */
  public static void validateInterestRate(BigDecimal rate, BigDecimal cap) {
    BigDecimal max = capOf(cap);
    if (rate == null) throw new IllegalArgumentException("이자율은 필수입니다.");
    if (rate.compareTo(ZERO) < 0) {
      throw new IllegalArgumentException("이자율은 0% 미만일 수 없습니다.");
    }
    if (rate.compareTo(max) > 0) {
      throw new IllegalArgumentException(
          "이자율이 상한(연 " + max.stripTrailingZeros().toPlainString() + "%)을 초과합니다. 입력값: " + rate + "%");
    }
  }

  /**
   * 연체이율 검증. 약정이율 + 3%p 와 최고이자율 중 낮은 값이 상한이다.
   * @param overdueRate  연체이율 (null 이면 검증 생략 — 연체이자 미부과 계약)
   * @param interestRate 약정이율
   */
  public static void validateOverdueRate(BigDecimal overdueRate, BigDecimal interestRate) {
    validateOverdueRate(overdueRate, interestRate, LEGAL_MAX_RATE);
  }

  /** 연체이율 검증 — 상한을 회사 설정으로 받는다. */
  public static void validateOverdueRate(BigDecimal overdueRate, BigDecimal interestRate, BigDecimal cap) {
    if (overdueRate == null) return;
    if (overdueRate.compareTo(ZERO) < 0) {
      throw new IllegalArgumentException("연체이율은 0% 미만일 수 없습니다.");
    }
    BigDecimal limit = maxOverdueRate(interestRate, cap);
    if (overdueRate.compareTo(limit) > 0) {
      throw new IllegalArgumentException(
          "연체이율이 상한(" + limit.stripTrailingZeros().toPlainString() + "%)을 초과합니다. "
              + "상한은 약정이율 + 3%p 와 이자율 상한 중 낮은 값입니다. 입력값: " + overdueRate + "%");
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
    validateEffectiveRate(effectiveRate, LEGAL_MAX_RATE);
  }

  /** 실효 연이율 검증 — 상한을 회사 설정으로 받는다. */
  public static void validateEffectiveRate(BigDecimal effectiveRate, BigDecimal cap) {
    BigDecimal max = capOf(cap);
    if (effectiveRate == null) return;
    if (effectiveRate.compareTo(max) <= 0) return;

    throw new IllegalArgumentException(
        "연이자율 상한 초과입니다."
      + " 단수 처리까지 반영한 실효 연이율이 "
      + effectiveRate.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
      + "% 로 상한(연 " + max.stripTrailingZeros().toPlainString() + "%)을 넘습니다."
      + " 단수 처리를 '절사'나 '반올림'으로 바꾸거나 약정이율을 낮추세요.");
  }

  /** 해당 약정이율에서 허용되는 연체이율 상한 */
  public static BigDecimal maxOverdueRate(BigDecimal interestRate) {
    return maxOverdueRate(interestRate, LEGAL_MAX_RATE);
  }

  /** 해당 약정이율에서 허용되는 연체이율 상한 — 상한을 회사 설정으로 받는다. */
  public static BigDecimal maxOverdueRate(BigDecimal interestRate, BigDecimal cap) {
    BigDecimal max = capOf(cap);
    if (interestRate == null) return max;
    BigDecimal spread = interestRate.add(OVERDUE_SPREAD_CAP);
    return spread.compareTo(max) > 0 ? max : spread;
  }

  /** 비어 있거나 0 이하면 법정 상한을 쓴다. */
  private static BigDecimal capOf(BigDecimal cap) {
    return (cap == null || cap.compareTo(ZERO) <= 0) ? LEGAL_MAX_RATE : cap;
  }

  private LoanRateValidator() {}
}
