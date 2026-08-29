package com.jdend.erp.loan.interest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 원리금균등 상환 스케줄 생성.
 *
 * <pre>
 *   월이율 r = 연이율 / 12
 *   월납입액 PMT = P × r × (1+r)^n / ((1+r)^n − 1)
 *   회차이자 = 직전 잔여원금 × r
 *   회차원금 = PMT − 회차이자
 * </pre>
 *
 * <p>마지막 회차에서 반올림으로 남은 잔돈을 원금에 흡수시켜 잔여원금이 정확히 0이 되게 한다.
 * 이렇게 하지 않으면 완제했는데 몇 원이 남는 일이 생긴다.
 *
 * <p>납기일은 첫 납기일에서 한 달씩 더한다. 31일처럼 없는 날짜는 그 달의 말일로 당긴다
 * ({@code LocalDate.plusMonths} 가 이 처리를 해 준다).
 *
 * <p>기존 {@code AmortizationCalculator} 와 계산식은 같지만, 이 모듈은 회차별
 * openingPrincipal 까지 돌려줘야 해서 스케줄 전체를 여기서 만든다.
 */
public final class EqualPaymentScheduleGenerator {

  private static final int SCALE = 16;
  private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

  public static List<ScheduledInstallment> generate(long principal, AnnualRate annualRate,
                                                    int termMonths, LocalDate firstDueDate) {
    if (principal <= 0 || termMonths <= 0 || firstDueDate == null) return List.of();

    BigDecimal monthlyRate = annualRate.asFraction().divide(TWELVE, SCALE, RoundingMode.HALF_UP);
    long payment = monthlyPayment(principal, monthlyRate, termMonths);

    List<ScheduledInstallment> out = new ArrayList<>(termMonths);
    long remaining = principal;

    for (int no = 1; no <= termMonths; no++) {
      LocalDate due = firstDueDate.plusMonths(no - 1L);
      long opening = remaining;

      long interest = BigDecimal.valueOf(opening).multiply(monthlyRate)
          .setScale(0, RoundingMode.HALF_UP).longValue();
      long principalPart = payment - interest;

      // 마지막 회차: 잔돈을 전부 털어 잔여원금을 0으로 맞춘다
      if (no == termMonths || principalPart > remaining) {
        principalPart = remaining;
      }
      if (principalPart < 0) principalPart = 0;

      remaining -= principalPart;

      out.add(new ScheduledInstallment(
          no, due, principalPart + interest, principalPart, interest, opening, remaining));
    }
    return out;
  }

  /** PMT. 무이자면 원금을 회차수로 나눈다. */
  static long monthlyPayment(long principal, BigDecimal monthlyRate, int months) {
    if (monthlyRate.signum() == 0) {
      return BigDecimal.valueOf(principal)
          .divide(BigDecimal.valueOf(months), 0, RoundingMode.HALF_UP).longValue();
    }
    BigDecimal one = BigDecimal.ONE;
    BigDecimal pow = one.add(monthlyRate).pow(months);
    return BigDecimal.valueOf(principal)
        .multiply(monthlyRate)
        .multiply(pow)
        .divide(pow.subtract(one), 0, RoundingMode.HALF_UP)
        .longValue();
  }

  private EqualPaymentScheduleGenerator() {}
}
