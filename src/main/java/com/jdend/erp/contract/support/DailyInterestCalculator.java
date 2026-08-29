package com.jdend.erp.contract.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 일할 이자 계산 — 중도상환·기한이익상실·완제 정산 시점에 사용한다.
 *
 * 이자 = 원금 × 연이율 / 100 / 365 × 경과일수
 * 윤년도 365일로 고정한다(대부업 실무 관행). 원 단위 절사.
 */
public final class DailyInterestCalculator {

  /** 연 일수 — 윤년 무관 고정 */
  public static final int DAYS_IN_YEAR = 365;

  private static final int SCALE = 12;

  /**
   * 기간 경과이자.
   * @param principal 이자가 붙는 원금
   * @param annualRatePercent 연이율(%)
   * @param from 기산일 (제외)
   * @param to   정산일 (포함)
   */
  public static long accrued(long principal, BigDecimal annualRatePercent, LocalDate from, LocalDate to) {
    return accrued(principal, annualRatePercent, daysBetween(from, to));
  }

  /** 일수를 직접 지정하는 형태 */
  public static long accrued(long principal, BigDecimal annualRatePercent, long days) {
    if (principal <= 0 || days <= 0 || annualRatePercent == null) return 0L;
    if (annualRatePercent.compareTo(BigDecimal.ZERO) <= 0) return 0L;

    return BigDecimal.valueOf(principal)
        .multiply(annualRatePercent)
        .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP)
        .divide(BigDecimal.valueOf(DAYS_IN_YEAR), SCALE, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(days))
        .setScale(0, RoundingMode.DOWN)
        .longValue();
  }

  /**
   * 지연배상금. 연체이자 미부과 계약(overdueChargeYn = false)이면 0을 반환한다.
   *
   * @param overdueCharged 계약의 연체이자 부과 여부
   * @param overduePrincipal 연체된 <b>원금</b>. 미납 이자는 넣지 않는다 —
   *        이자에 지연배상금을 붙이면 복리가 되어 이자제한법 제한을 받는다.
   * @param overdueRatePercent 연체이율(%)
   * @param dueDate 납입예정일 — 연체는 D+1부터 기산한다
   * @param asOf 기준일
   */
  public static long overdueInterest(boolean overdueCharged, long overduePrincipal,
                                     BigDecimal overdueRatePercent, LocalDate dueDate, LocalDate asOf) {
    if (!overdueCharged) return 0L;
    return accrued(overduePrincipal, overdueRatePercent, overdueDays(dueDate, asOf));
  }

  /** 연체일수 — 납입예정일 다음날(D+1)부터 기산 */
  public static long overdueDays(LocalDate dueDate, LocalDate asOf) {
    if (dueDate == null || asOf == null) return 0L;
    long days = ChronoUnit.DAYS.between(dueDate, asOf);
    return Math.max(0L, days);
  }

  private static long daysBetween(LocalDate from, LocalDate to) {
    if (from == null || to == null) return 0L;
    return Math.max(0L, ChronoUnit.DAYS.between(from, to));
  }

  private DailyInterestCalculator() {}
}
