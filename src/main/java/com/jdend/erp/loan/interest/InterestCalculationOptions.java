package com.jdend.erp.loan.interest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 이자 계산의 반올림·일수 정책.
 *
 * <p>기본값은 스펙에 명시된 대로 <b>일자별 절사가 아니라 회차 단위 최종 반올림</b>,
 * <b>연 365일 고정</b>이다.
 *
 * <p>일자별로 절사하면 회차가 많을수록 오차가 한쪽으로만 쌓인다.
 * 예를 들어 하루치가 1,234.9원인데 매일 절사하면 30일이면 27원이 사라진다.
 * 그래서 고정밀도로 곱해 두었다가 회차 끝에서 한 번만 반올림한다.
 *
 * <p>기존 {@code DailyInterestCalculator} 는 절사(DOWN)를 쓴다. 정산 화면과 이 모듈의
 * 값이 몇 원 단위로 다를 수 있는데, 그건 정책 차이지 버그가 아니다.
 */
public record InterestCalculationOptions(
    DayCount dayCount,
    Rounding rounding
) {

  /** 연 일수 기준 */
  public enum DayCount {
    /** 윤년도 365일로 본다. 대부업 실무 관행이자 이 프로젝트의 기존 기준. */
    FIXED_365,
    /** 해당 기간이 윤년을 지나면 366일로 본다. */
    ACTUAL_365_366
  }

  /** 원 단위 처리 시점 */
  public enum Rounding {
    /** 기본값. 회차 단위로 마지막에 한 번 반올림(HALF_UP). */
    FINAL_HALF_UP,
    /** 하루치를 매일 절사한 뒤 합산. 보수적이지만 오차가 누적된다. */
    PER_DIEM_FLOOR
  }

  public static InterestCalculationOptions defaults() {
    return new InterestCalculationOptions(DayCount.FIXED_365, Rounding.FINAL_HALF_UP);
  }

  /**
   * 기간에 적용할 연 일수.
   * ACTUAL 이면 기간에 2월 29일이 포함될 때 366을 쓴다.
   */
  public BigDecimal daysInYear(LocalDate from, LocalDate to) {
    if (dayCount == DayCount.FIXED_365 || from == null || to == null) {
      return BigDecimal.valueOf(365);
    }
    for (int y = from.getYear(); y <= to.getYear(); y++) {
      if (java.time.Year.isLeap(y)) {
        LocalDate feb29 = LocalDate.of(y, 2, 29);
        if (!feb29.isBefore(from) && !feb29.isAfter(to)) return BigDecimal.valueOf(366);
      }
    }
    return BigDecimal.valueOf(365);
  }

  public RoundingMode finalRoundingMode() {
    return rounding == Rounding.PER_DIEM_FLOOR ? RoundingMode.DOWN : RoundingMode.HALF_UP;
  }

  public String describe() {
    String dc = dayCount == DayCount.FIXED_365 ? "연 365일 고정" : "실제 일수(윤년 366일)";
    String rd = rounding == Rounding.FINAL_HALF_UP
        ? "회차 단위 최종 반올림(HALF_UP)"
        : "일자별 절사 후 합산(FLOOR)";
    return "반올림 정책: " + rd + " / 일수 기준: " + dc;
  }
}
