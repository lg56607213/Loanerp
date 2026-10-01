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
    return monthlyPayment(repaymentMethod, principal, annualRatePercent, months, AmountRounding.DEFAULT);
  }

  /**
   * 단수 처리를 지정한 월납입액.
   *
   * 백원 단위로 약정하는 계약을 위해 회차 금액을 끊는다.
   * 원금균등은 원금과 이자를 각각 끊어 더한다 — 합계만 끊으면 회차 안에서
   * 원금+이자가 납입액과 어긋난다.
   */
  public static long monthlyPayment(String repaymentMethod, long principal,
                                    BigDecimal annualRatePercent, int months,
                                    AmountRounding rounding) {
    if (principal <= 0 || months <= 0) return 0L;
    AmountRounding r0 = rounding == null ? AmountRounding.DEFAULT : rounding;
    BigDecimal r = monthlyRate(annualRatePercent);

    if (RepaymentMethod.BULLET.equals(repaymentMethod)) {
      return r0.apply(BigDecimal.valueOf(principal).multiply(r));
    }
    if (RepaymentMethod.EQUAL_PRINCIPAL.equals(repaymentMethod)) {
      long principalPart = r0.apply(BigDecimal.valueOf(principal)
          .divide(BigDecimal.valueOf(months), SCALE, RoundingMode.HALF_UP));
      long interestPart = r0.apply(BigDecimal.valueOf(principal).multiply(r));
      return principalPart + interestPart;
    }
    return equalPayment(principal, r, months, r0);
  }

  /**
   * 원리금균등 PMT.
   *   PMT = P × r × (1+r)^n / ((1+r)^n − 1)
   * 무이자(r = 0)면 원금을 회차로 나눈다.
   */
  public static long equalPayment(long principal, BigDecimal monthlyRate, int months) {
    return equalPayment(principal, monthlyRate, months, AmountRounding.DEFAULT);
  }

  /** 단수 처리를 지정한 원리금균등 PMT. 끊은 값이 그대로 매회 납입액이 된다. */
  public static long equalPayment(long principal, BigDecimal monthlyRate, int months,
                                  AmountRounding rounding) {
    if (principal <= 0 || months <= 0) return 0L;
    AmountRounding r0 = rounding == null ? AmountRounding.DEFAULT : rounding;
    if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
      return r0.apply(BigDecimal.valueOf(principal)
          .divide(BigDecimal.valueOf(months), SCALE, RoundingMode.HALF_UP));
    }
    BigDecimal one = BigDecimal.ONE;
    BigDecimal onePlusR = one.add(monthlyRate);
    BigDecimal pow = onePlusR.pow(months);

    BigDecimal numerator = BigDecimal.valueOf(principal).multiply(monthlyRate).multiply(pow);
    BigDecimal denominator = pow.subtract(one);

    return r0.apply(numerator.divide(denominator, SCALE, RoundingMode.HALF_UP));
  }

  /** 잔여원금에 붙는 월 이자 (원 단위 반올림) */
  public static long monthlyInterest(long remainingPrincipal, BigDecimal monthlyRate) {
    return monthlyInterest(remainingPrincipal, monthlyRate, AmountRounding.DEFAULT);
  }

  /** 단수 처리를 지정한 월 이자 */
  public static long monthlyInterest(long remainingPrincipal, BigDecimal monthlyRate,
                                     AmountRounding rounding) {
    if (remainingPrincipal <= 0) return 0L;
    AmountRounding r0 = rounding == null ? AmountRounding.DEFAULT : rounding;
    return r0.apply(BigDecimal.valueOf(remainingPrincipal).multiply(monthlyRate));
  }

  private AmortizationCalculator() {}
}
