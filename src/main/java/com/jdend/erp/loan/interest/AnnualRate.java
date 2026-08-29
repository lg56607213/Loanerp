package com.jdend.erp.loan.interest;

import com.jdend.erp.contract.support.LoanRateValidator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 연이율 값 타입.
 *
 * <p><b>왜 타입으로 감쌌나.</b> 이 프로젝트는 이율을 퍼센트(18.00)로 저장하는데
 * 계산 스펙은 소수(0.18)를 요구한다. 둘 다 그냥 {@code BigDecimal} 로 들고 다니면
 * 어느 쪽인지 호출부에서 알 수 없고, 100배 틀린 이자가 조용히 계산된다.
 * 생성자를 {@link #ofPercent}/{@link #ofFraction} 으로만 열어 단위를 강제한다.
 *
 * <p>내부 보관은 소수(fraction)다. 계산은 소수로 하고, 화면·엔티티 연동은
 * {@link #asPercent()} 로 변환한다.
 */
public final class AnnualRate {

  /** 나눗셈 중간 정밀도. 이율은 여기서 반올림하지 않는다(최종 금액에서 한 번만 반올림). */
  private static final int SCALE = 16;

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  public static final AnnualRate ZERO = new AnnualRate(BigDecimal.ZERO);

  /** 대부업법 최고이자율. LoanRateValidator 와 같은 값을 쓴다(상한 정의는 한 곳에만 둔다). */
  public static final AnnualRate LEGAL_MAX = ofPercent(LoanRateValidator.MAX_RATE);

  /** 연체이율 가산폭 (+3%p) */
  public static final AnnualRate OVERDUE_SPREAD = ofPercent(LoanRateValidator.OVERDUE_SPREAD_CAP);

  private final BigDecimal fraction;

  private AnnualRate(BigDecimal fraction) {
    this.fraction = Objects.requireNonNull(fraction, "fraction");
  }

  /** 퍼센트 표기로 만든다. 18.00 -> 연 18% */
  public static AnnualRate ofPercent(BigDecimal percent) {
    if (percent == null) return ZERO;
    return new AnnualRate(percent.divide(HUNDRED, SCALE, RoundingMode.HALF_UP));
  }

  /** 퍼센트 표기로 만든다. 18 -> 연 18% */
  public static AnnualRate ofPercent(double percent) {
    return ofPercent(BigDecimal.valueOf(percent));
  }

  /** 소수 표기로 만든다. 0.18 -> 연 18% */
  public static AnnualRate ofFraction(BigDecimal fraction) {
    if (fraction == null) return ZERO;
    return new AnnualRate(fraction);
  }

  /** 소수 표기로 만든다. 0.18 -> 연 18% */
  public static AnnualRate ofFraction(double fraction) {
    return ofFraction(BigDecimal.valueOf(fraction));
  }

  /**
   * 연체이율 = min(약정이율 + 3%p, 20%).
   *
   * <p>여기서 나온 값은 <b>정상이자까지 포함한 최종 연이율</b>이다.
   * 약정이율에 이 값을 다시 더하면 안 된다(18% + 20% = 38% 같은 계산이 대표적인 사고).
   */
  public static AnnualRate defaultRateOf(AnnualRate contractRate) {
    return contractRate.plus(OVERDUE_SPREAD).cappedAt(LEGAL_MAX);
  }

  /** 상한 적용 전의 산출값 — 감사 로그에 남기려고 따로 노출한다. */
  public static AnnualRate calculatedDefaultRateOf(AnnualRate contractRate) {
    return contractRate.plus(OVERDUE_SPREAD);
  }

  public AnnualRate plus(AnnualRate other) {
    return new AnnualRate(fraction.add(other.fraction));
  }

  public AnnualRate minus(AnnualRate other) {
    BigDecimal v = fraction.subtract(other.fraction);
    return new AnnualRate(v.signum() < 0 ? BigDecimal.ZERO : v);
  }

  public AnnualRate cappedAt(AnnualRate max) {
    return fraction.compareTo(max.fraction) > 0 ? max : this;
  }

  /** 계산용 소수값 (0.18) */
  public BigDecimal asFraction() {
    return fraction;
  }

  /** 화면·엔티티용 퍼센트값 (18.00) */
  public BigDecimal asPercent() {
    return fraction.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
  }

  public boolean isPositive() {
    return fraction.signum() > 0;
  }

  public boolean isGreaterThan(AnnualRate other) {
    return fraction.compareTo(other.fraction) > 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AnnualRate other)) return false;
    return fraction.compareTo(other.fraction) == 0;
  }

  @Override
  public int hashCode() {
    return fraction.stripTrailingZeros().hashCode();
  }

  /** 로그·메시지용. "연 18%" */
  @Override
  public String toString() {
    return "연 " + asPercent().stripTrailingZeros().toPlainString() + "%";
  }
}
