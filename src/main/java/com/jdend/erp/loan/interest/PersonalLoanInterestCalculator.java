package com.jdend.erp.loan.interest;

import com.jdend.erp.loan.policy.LegacyAccelerationDefaultInterestPolicy;
import com.jdend.erp.loan.policy.PersonalDebtorProtection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 개인금융채권 연체이자 계산.
 *
 * <p><b>이 클래스가 지키는 두 가지 규칙</b>
 *
 * <p>1. 연체이율은 정상이자를 <b>포함한</b> 최종 이율이다.
 * 약정 18%면 연체이율은 min(18+3, 20) = 20% 이고, 연체원금에는 20%를 적용한다.
 * 18% 와 20% 를 더해 38% 로 계산하지 않는다. 그래서 회차별 이자를
 * '약정이율분(ordinary)' 과 '초과분(default)' 으로 쪼개 두 값의 합이 정확히
 * 연체이율만큼 되게 한다.
 *
 * <p>2. 기한이익상실이 나도 <b>원래 납기일이 도래하지 않은 원금에는 연체가산이자를 붙이지 않는다.</b>
 * (개인금융채권의 관리 및 개인금융채무자의 보호에 관한 법률)
 * 회차 상태 판정은 언제나 <b>원래 계약상 납기일</b>로 한다. 기한이익상실일을 기준으로
 * 미래 회차를 일괄 연체 처리하지 않는다.
 *
 * <p>계산은 {@link BigDecimal} 로 하고 회차 단위로 한 번만 원 단위 반올림한다.
 * 날짜는 {@link LocalDate} 만 쓴다(시간대 개입 없음).
 */
public final class PersonalLoanInterestCalculator {

  private static final int SCALE = 16;

  /** 스케줄 생성 + 청구액 계산을 한 번에 */
  public static LoanChargeResult calculate(LoanInterestInput input) {
    List<ScheduledInstallment> schedule = generateSchedule(input);
    return calculateAccruedCharges(input, schedule);
  }

  /** 원리금균등 스케줄 생성 */
  public static List<ScheduledInstallment> generateSchedule(LoanInterestInput input) {
    return EqualPaymentScheduleGenerator.generate(
        input.getOriginalPrincipal(), input.getContractRate(),
        input.getTermMonths(), input.getFirstDueDate());
  }

  /**
   * 기준일 현재의 회차별 원금·약정이자·연체가산이자를 산출한다.
   */
  public static LoanChargeResult calculateAccruedCharges(LoanInterestInput input,
                                                         List<ScheduledInstallment> schedule) {
    AnnualRate contractRate = input.getContractRate();
    AnnualRate calculatedDefaultRate = AnnualRate.calculatedDefaultRateOf(contractRate);
    AnnualRate defaultRate = AnnualRate.defaultRateOf(contractRate);
    /** 연체이율에서 약정이율을 뺀 '가산분'. 법이 제한하는 대상이 이것이다. */
    AnnualRate spread = defaultRate.minus(contractRate);

    boolean protectionApplies = PersonalDebtorProtection.applies(
        input.getOriginalPrincipal(), input.getDebtorType(), input.getDebtType());

    LocalDate asOf = input.getAsOfDate();
    LocalDate eod = input.getEodEffectiveDate();
    InterestCalculationOptions opt = input.getOptions();

    List<String> logs = new ArrayList<>();
    logs.add("연체이율 = min(약정이율 + 3%p, 20%) → min("
        + contractRate.asPercent().stripTrailingZeros().toPlainString() + "% + 3%, 20%) = "
        + defaultRate.asPercent().stripTrailingZeros().toPlainString() + "%");
    logs.add("연체이율은 정상이자를 포함한 최종 이율이다. 약정이율과 연체이율을 더해 적용하지 않는다."
        + " (약정 " + contractRate + " / 가산분 " + spread + " / 합계 " + defaultRate + ")");
    logs.add("기한의 이익 상실 이후에도 원래 납기일이 도래하지 않은 원금에는 연체가산이자를 부과하지 않음");
    logs.add(PersonalDebtorProtection.describe(
        input.getOriginalPrincipal(), input.getDebtorType(), input.getDebtType()));
    logs.add("기한이익상실 효력일: " + (eod == null ? "없음" : eod.toString())
        + " / 계산 기준일: " + asOf);
    if (eod != null && !protectionApplies) {
      logs.add("보호법 미적용 채권의 기한이익상실 처리 정책: " + input.getLegacyAccelerationPolicy());
    }
    logs.add(opt.describe());

    PaidAmounts[] paid = allocatePayments(input, schedule);

    List<InstallmentCharge> charges = new ArrayList<>(schedule.size());
    long remainingPrincipal = 0;
    long totalScheduledInterest = 0;
    long totalOrdinary = 0;
    long totalDefault = 0;

    for (int i = 0; i < schedule.size(); i++) {
      ScheduledInstallment s = schedule.get(i);
      PaidAmounts p = paid[i];

      totalScheduledInterest += s.scheduledInterest();

      long overduePrincipal = Math.max(0, s.scheduledPrincipal() - p.principal);
      remainingPrincipal += overduePrincipal;

      // ── 상태 판정: 언제나 '원래 납기일' 기준 ──────────────────
      boolean matured = !s.dueDate().isAfter(asOf);          // dueDate <= asOfDate
      boolean fullyPaid = overduePrincipal == 0;

      InstallmentStatus status;
      if (fullyPaid) {
        status = InstallmentStatus.PAID;
      } else if (matured) {
        status = InstallmentStatus.DUE_UNPAID;
      } else if (eod != null && !eod.isAfter(asOf)) {
        // 기한이익상실로 변제기는 앞당겨졌지만, 원래 납기일은 아직 미도래다.
        status = InstallmentStatus.EOD_ACCELERATED;
      } else {
        status = InstallmentStatus.FUTURE;
      }

      // ── 이자 기산 구간 ──────────────────────────────────────
      LocalDate from = null;
      LocalDate to = null;
      long days = 0;
      long base = 0;
      AnnualRate applicable = contractRate;
      long ordinary = 0;
      long deflt = 0;

      switch (status) {
        case PAID -> {
          // 늦게 냈다면 그 기간만큼 연체이자가 이미 발생했다.
          LocalDate payDate = p.settledOn;
          if (payDate != null && payDate.isAfter(s.dueDate())) {
            from = s.dueDate();
            to = payDate;
            days = daysBetween(from, to);
            base = s.scheduledPrincipal();
            applicable = defaultRate;
            ordinary = interest(base, contractRate, days, opt, from, to);
            deflt = interest(base, spread, days, opt, from, to);
          }
        }
        case DUE_UNPAID -> {
          // 원래 납기일이 도래했고 원금이 남았다 → 연체이율(정상+가산) 적용
          from = s.dueDate();
          to = asOf;
          days = daysBetween(from, to);
          base = overduePrincipal;
          applicable = defaultRate;
          ordinary = interest(base, contractRate, days, opt, from, to);
          deflt = interest(base, spread, days, opt, from, to);
        }
        case EOD_ACCELERATED -> {
          // 핵심. 원래 납기일이 미도래이므로 가산이자는 붙이지 않는다.
          // 조기 변제기가 도래한 원금에 약정이율만 적용한다.
          boolean chargeSpread = !protectionApplies
              && input.getLegacyAccelerationPolicy()
                 == LegacyAccelerationDefaultInterestPolicy.DEFAULT_INTEREST_ON_ENTIRE_BALANCE;

          from = eod;
          to = asOf;
          days = daysBetween(from, to);
          base = overduePrincipal;
          applicable = chargeSpread ? defaultRate : contractRate;
          ordinary = interest(base, contractRate, days, opt, from, to);
          deflt = chargeSpread ? interest(base, spread, days, opt, from, to) : 0L;
        }
        case FUTURE -> {
          // 아직 납기일이 오지 않았다. 기준일 현재 청구 대상이 아니므로 발생액 0.
          // 이 회차의 약정이자는 scheduledInterest 에 이미 잡혀 있다(청구 시점에 인식).
          applicable = contractRate;
        }
      }

      totalOrdinary += ordinary;
      totalDefault += deflt;

      long overdueDays = (status == InstallmentStatus.DUE_UNPAID
                       || (status == InstallmentStatus.PAID && days > 0)) ? days : 0L;

      charges.add(InstallmentCharge.builder()
          .installmentNo(s.installmentNo())
          .dueDate(s.dueDate())
          .scheduledPayment(s.scheduledPayment())
          .scheduledPrincipal(s.scheduledPrincipal())
          .scheduledInterest(s.scheduledInterest())
          .paidPrincipal(p.principal)
          .paidInterest(p.interest)
          .paidDefaultInterest(p.defaultInterest)
          .overduePrincipal(overduePrincipal)
          .ordinaryInterestAccrued(ordinary)
          .defaultInterestAccrued(deflt)
          .overdueDays(overdueDays)
          .status(status)
          .applicableAnnualRate(applicable)
          .accrualFrom(from)
          .accrualTo(to)
          .interestBasePrincipal(base)
          .build());

      if (days > 0 || overduePrincipal > 0) {
        logs.add(String.format(
            "%d회차 | 원래납기일 %s | 상태 %s | 미납원금 %,d원 | 기산 %s~%s (%d일) | 적용이율 %s | 약정이자 %,d원 + 가산이자 %,d원",
            s.installmentNo(), s.dueDate(), status, overduePrincipal,
            from == null ? "-" : from.plusDays(1).toString(),
            to == null ? "-" : to.toString(),
            days, applicable, ordinary, deflt));
      }
    }

    long totalPayable = remainingPrincipal + totalOrdinary + totalDefault;

    return LoanChargeResult.builder()
        .contractRate(contractRate)
        .calculatedDefaultRate(calculatedDefaultRate)
        .defaultRate(defaultRate)
        .personalDebtorProtectionApplies(protectionApplies)
        .eodEffectiveDate(eod)
        .asOfDate(asOf)
        .remainingPrincipal(remainingPrincipal)
        .totalScheduledInterest(totalScheduledInterest)
        .totalOrdinaryInterestAccrued(totalOrdinary)
        .totalDefaultInterestAccrued(totalDefault)
        .totalPayable(totalPayable)
        .installments(charges)
        .auditLogs(logs)
        .build();
  }

  // ── 이자 계산 ────────────────────────────────────────────────

  /**
   * 일할 이자 = 원금 × 연이율 × 일수 / 연일수.
   * 기본 정책은 고정밀도로 계산한 뒤 <b>마지막에 한 번</b> 원 단위 반올림한다.
   */
  static long interest(long principal, AnnualRate rate, long days,
                       InterestCalculationOptions opt, LocalDate from, LocalDate to) {
    if (principal <= 0 || days <= 0 || rate == null || !rate.isPositive()) return 0L;

    BigDecimal daysInYear = opt.daysInYear(from, to);

    if (opt.rounding() == InterestCalculationOptions.Rounding.PER_DIEM_FLOOR) {
      long perDiem = BigDecimal.valueOf(principal)
          .multiply(rate.asFraction())
          .divide(daysInYear, SCALE, RoundingMode.HALF_UP)
          .setScale(0, RoundingMode.DOWN)
          .longValue();
      return perDiem * days;
    }

    return BigDecimal.valueOf(principal)
        .multiply(rate.asFraction())
        .multiply(BigDecimal.valueOf(days))
        .divide(daysInYear, SCALE, RoundingMode.HALF_UP)
        .setScale(0, opt.finalRoundingMode())
        .longValue();
  }

  /**
   * 연체일수. 납기일 다음날부터 기산하고 납기일 당일 납부는 0일이다.
   * {@code ChronoUnit.DAYS.between(dueDate, to)} 가 정확히 그 값이다
   * (dueDate 당일이면 0, 하루 지나면 1).
   */
  static long daysBetween(LocalDate dueDate, LocalDate to) {
    if (dueDate == null || to == null) return 0L;
    return Math.max(0L, ChronoUnit.DAYS.between(dueDate, to));
  }

  // ── 납입 배분 ────────────────────────────────────────────────

  /** 회차별 납입 실적 */
  private static final class PaidAmounts {
    long principal;
    long interest;
    long defaultInterest;
    /** 이 회차 원금이 전액 채워진 날 */
    LocalDate settledOn;
  }

  /**
   * 납입을 회차에 배분한다.
   *
   * <p>배분 결과가 이미 들어온 납입은 그대로 오래된 회차부터 채운다.
   * 총액만 들어온 납입은 이 프로젝트의 변제충당 순서를 따라
   * <b>연체가산이자 → 약정이자 → 원금</b> 순으로, 오래된 회차부터 채운다.
   * (법적비용은 이 모듈의 관심사가 아니라 {@code loan.repayment} 가 다룬다)
   */
  private static PaidAmounts[] allocatePayments(LoanInterestInput input,
                                                List<ScheduledInstallment> schedule) {
    PaidAmounts[] paid = new PaidAmounts[schedule.size()];
    for (int i = 0; i < paid.length; i++) paid[i] = new PaidAmounts();

    List<LoanPaymentRecord> payments = new ArrayList<>(
        input.getPayments() == null ? List.<LoanPaymentRecord>of() : input.getPayments());
    payments.sort(Comparator.comparing(LoanPaymentRecord::paymentDate,
        Comparator.nullsLast(Comparator.naturalOrder())));

    AnnualRate contractRate = input.getContractRate();
    AnnualRate defaultRate = AnnualRate.defaultRateOf(contractRate);
    AnnualRate spread = defaultRate.minus(contractRate);

    for (LoanPaymentRecord pay : payments) {
      LocalDate payDate = pay.paymentDate();

      if (pay.isPreAllocated()) {
        Integer target = pay.installmentNo();
        if (target != null && target >= 1 && target <= paid.length) {
          // 회차가 지정된 실적은 그 회차에 그대로 꽂는다
          PaidAmounts t = paid[target - 1];
          t.principal += pay.principalOrZero();
          t.interest += pay.interestOrZero();
          t.defaultInterest += pay.lateInterestOrZero();
          if (t.principal >= schedule.get(target - 1).scheduledPrincipal()) t.settledOn = payDate;
        } else {
          fill(paid, schedule, payDate, pay.principalOrZero(), pay.interestOrZero(), pay.lateInterestOrZero());
        }
        continue;
      }

      long remain = pay.amount();
      // 1) 연체가산이자 → 2) 약정이자 → 3) 원금, 각각 오래된 회차부터
      for (int i = 0; i < schedule.size() && remain > 0; i++) {
        ScheduledInstallment s = schedule.get(i);
        if (s.dueDate().isAfter(payDate)) break;   // 아직 도래하지 않은 회차는 건너뛴다
        long unpaidPrincipal = Math.max(0, s.scheduledPrincipal() - paid[i].principal);
        if (unpaidPrincipal <= 0) continue;
        long days = daysBetween(s.dueDate(), payDate);
        long due = interest(unpaidPrincipal, spread, days, input.getOptions(), s.dueDate(), payDate)
                 - paid[i].defaultInterest;
        if (due <= 0) continue;
        long take = Math.min(remain, due);
        paid[i].defaultInterest += take;
        remain -= take;
      }
      for (int i = 0; i < schedule.size() && remain > 0; i++) {
        ScheduledInstallment s = schedule.get(i);
        if (s.dueDate().isAfter(payDate)) break;
        long due = s.scheduledInterest() - paid[i].interest;
        if (due <= 0) continue;
        long take = Math.min(remain, due);
        paid[i].interest += take;
        remain -= take;
      }
      for (int i = 0; i < schedule.size() && remain > 0; i++) {
        ScheduledInstallment s = schedule.get(i);
        long due = s.scheduledPrincipal() - paid[i].principal;
        if (due <= 0) continue;
        long take = Math.min(remain, due);
        paid[i].principal += take;
        remain -= take;
        if (paid[i].principal >= s.scheduledPrincipal()) paid[i].settledOn = payDate;
      }
    }
    return paid;
  }

  /** 배분 결과가 이미 있는 납입을 오래된 회차부터 채운다. */
  private static void fill(PaidAmounts[] paid, List<ScheduledInstallment> schedule,
                           LocalDate payDate, long principal, long interest, long lateInterest) {
    long p = principal, in = interest, li = lateInterest;
    for (int i = 0; i < schedule.size(); i++) {
      ScheduledInstallment s = schedule.get(i);
      if (li > 0) { paid[i].defaultInterest += li; li = 0; }
      if (in > 0) {
        long due = Math.max(0, s.scheduledInterest() - paid[i].interest);
        long take = Math.min(in, due);
        paid[i].interest += take; in -= take;
      }
      if (p > 0) {
        long due = Math.max(0, s.scheduledPrincipal() - paid[i].principal);
        long take = Math.min(p, due);
        paid[i].principal += take; p -= take;
        if (take > 0 && paid[i].principal >= s.scheduledPrincipal()) paid[i].settledOn = payDate;
      }
      if (p == 0 && in == 0 && li == 0) break;
    }
  }

  private PersonalLoanInterestCalculator() {}
}
