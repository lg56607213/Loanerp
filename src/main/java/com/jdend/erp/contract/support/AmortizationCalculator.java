package com.jdend.erp.contract.support;

import com.jdend.erp.contract.entity.RepaymentMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 상환 계산 엔진 — 월할.
 *
 * 정기 회차 스케줄은 월할(연이율 ÷ 12)로 산출하고,
 * 중도상환·기한이익상실 등 정산 시점은 {@link DailyInterestCalculator}로 일할 재계산한다.
 */
public final class AmortizationCalculator {

  private static final int SCALE = 12;

  /** 월이율 = 연이율 / 100 / 12 */
  public static BigDecimal monthlyRate(BigDecimal annualRatePercent) {
    if (annualRatePercent == null) return BigDecimal.ZERO;
    return annualRatePercent
        .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP)
        .divide(BigDecimal.valueOf(12), SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 상환방식별 월납입액.
   *
   * <ul>
   *   <li>원리금균등 — PMT 공식으로 산출 (매회 동일)</li>
   *   <li>원금균등 — 1회차 납입액(원금 + 최초 이자). 회차마다 달라지므로 참고값</li>
   *   <li>만기일시 — 월 이자만</li>
   * </ul>
   */
  public static long monthlyPayment(String repaymentMethod, long principal,
                                    BigDecimal annualRatePercent, int months) {
    if (principal <= 0 || months <= 0) return 0L;
    BigDecimal r = monthlyRate(annualRatePercent);

    if (RepaymentMethod.BULLET.equals(repaymentMethod)) {
      return BigDecimal.valueOf(principal).multiply(r).setScale(0, RoundingMode.HALF_UP).longValue();
    }
    if (RepaymentMethod.EQUAL_PRINCIPAL.equals(repaymentMethod)) {
      long principalPart = Math.round((double) principal / months);
      long interestPart = BigDecimal.valueOf(principal).multiply(r)
          .setScale(0, RoundingMode.HALF_UP).longValue();
      return principalPart + interestPart;
    }
    return equalPayment(principal, r, months);
  }

  /**
   * 원리금균등 PMT.
   *   PMT = P × r × (1+r)^n / ((1+r)^n − 1)
   * 무이자(r = 0)면 원금을 회차로 나눈다.
   */
  public static long equalPayment(long principal, BigDecimal monthlyRate, int months) {
    if (principal <= 0 || months <= 0) return 0L;
    if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
      return Math.round((double) principal / months);
    }
    BigDecimal one = BigDecimal.ONE;
    BigDecimal onePlusR = one.add(monthlyRate);
    BigDecimal pow = onePlusR.pow(months);

    BigDecimal numerator = BigDecimal.valueOf(principal).multiply(monthlyRate).multiply(pow);
    BigDecimal denominator = pow.subtract(one);

    return numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValue();
  }

  /** 잔여원금에 붙는 월 이자 (원 단위 반올림) */
  public static long monthlyInterest(long remainingPrincipal, BigDecimal monthlyRate) {
    if (remainingPrincipal <= 0) return 0L;
    return BigDecimal.valueOf(remainingPrincipal).multiply(monthlyRate)
        .setScale(0, RoundingMode.HALF_UP).longValue();
  }

  private AmortizationCalculator() {}
}
