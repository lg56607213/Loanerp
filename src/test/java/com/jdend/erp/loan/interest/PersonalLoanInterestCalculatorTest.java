package com.jdend.erp.loan.interest;

import com.jdend.erp.loan.policy.DebtType;
import com.jdend.erp.loan.policy.DebtorType;
import com.jdend.erp.loan.policy.LegacyAccelerationDefaultInterestPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개인금융채권 연체이자 계산 검증.
 *
 * 핵심은 두 가지다.
 *   - 연체이율은 min(약정 + 3%p, 20%) 이고, 정상이자를 포함한 최종 이율이다.
 *   - 기한이익상실이 나도 원래 납기일이 도래하지 않은 원금에는 가산이자를 붙이지 않는다.
 */
class PersonalLoanInterestCalculatorTest {

  private LoanInterestInput.LoanInterestInputBuilder personalLoan(long principal, double ratePercent) {
    return LoanInterestInput.builder()
        .originalPrincipal(principal)
        .contractRate(AnnualRate.ofPercent(ratePercent))
        .termMonths(24)
        .startDate(LocalDate.of(2026, 1, 10))
        .firstDueDate(LocalDate.of(2026, 2, 10))
        .debtorType(DebtorType.INDIVIDUAL)
        .debtType(DebtType.PERSONAL_FINANCIAL_CLAIM);
  }

  /** n회차까지 정상 납부했다고 보는 납입 목록 (해당 회차 납기일에 회차 납입액을 낸다) */
  private List<LoanPaymentRecord> paidThrough(List<ScheduledInstallment> schedule, int lastPaidNo) {
    return schedule.stream()
        .filter(s -> s.installmentNo() <= lastPaidNo)
        .map(s -> LoanPaymentRecord.of(s.dueDate(), s.scheduledPayment()))
        .toList();
  }

  private InstallmentCharge at(LoanChargeResult r, int no) {
    return r.getInstallments().get(no - 1);
  }

  // ══════════════════════════════════════════════════════════════
  @Nested
  @DisplayName("테스트 1 — 3,000만원 / 18% / 24개월, 4·5·6회차 미납 후 EOD")
  class Test1 {

    private LoanChargeResult run() {
      // 회차 납기일: 4회차 2026-05-10 / 5회차 06-10 / 6회차 07-10 / 7회차 08-10
      // 기준일은 EOD(07-20) 이후이면서 7회차 납기일(08-10) 전이어야
      // 4~6회차만 도래·미납, 7회차부터 미도래가 된다.
      LoanInterestInput base = personalLoan(30_000_000L, 18.0).asOfDate(LocalDate.of(2026, 8, 5)).build();
      List<ScheduledInstallment> schedule = PersonalLoanInterestCalculator.generateSchedule(base);

      // 1~3회차만 납부. 4·5·6회차(2026-05-10, 06-10, 07-10) 미납.
      // 6회차 납기일 이후인 2026-07-20 에 기한이익상실 효력 발생.
      LoanInterestInput input = base.toBuilder()
          .payments(paidThrough(schedule, 3))
          .eodEffectiveDate(LocalDate.of(2026, 7, 20))
          .build();

      return PersonalLoanInterestCalculator.calculateAccruedCharges(input, schedule);
    }

    @Test
    @DisplayName("연체이율 = min(18% + 3%, 20%) = 20%")
    void defaultRateIsCapped() {
      LoanChargeResult r = run();
      assertThat(r.getContractRate()).isEqualTo(AnnualRate.ofPercent(18));
      assertThat(r.getCalculatedDefaultRate()).isEqualTo(AnnualRate.ofPercent(21));
      assertThat(r.getDefaultRate()).isEqualTo(AnnualRate.ofPercent(20));
      assertThat(r.isPersonalDebtorProtectionApplies()).isTrue();
    }

    @Test
    @DisplayName("4·5·6회차만 도래·미납이고 연체이율 20%가 적용된다")
    void onlyMaturedUnpaidInstallmentsGetDefaultRate() {
      LoanChargeResult r = run();

      for (int no : new int[]{4, 5, 6}) {
        InstallmentCharge c = at(r, no);
        assertThat(c.getStatus())
            .as("%d회차", no).isEqualTo(InstallmentStatus.DUE_UNPAID);
        assertThat(c.getApplicableAnnualRate())
            .as("%d회차 적용이율", no).isEqualTo(AnnualRate.ofPercent(20));
        assertThat(c.getDefaultInterestAccrued())
            .as("%d회차 가산이자", no).isPositive();
      }
    }

    @Test
    @DisplayName("7~24회차는 미도래 원금이라 가산이자가 0이고 약정이율 18%만 적용된다")
    void futureInstallmentsGetNoDefaultInterest() {
      LoanChargeResult r = run();

      long futureDefaultSum = 0;
      for (int no = 7; no <= 24; no++) {
        InstallmentCharge c = at(r, no);
        assertThat(c.getDefaultInterestAccrued())
            .as("%d회차 가산이자는 0이어야 한다", no).isZero();
        assertThat(c.getApplicableAnnualRate())
            .as("%d회차 적용이율", no).isEqualTo(AnnualRate.ofPercent(18));
        futureDefaultSum += c.getDefaultInterestAccrued();
      }
      assertThat(futureDefaultSum).isZero();
    }

    @Test
    @DisplayName("EOD 이후 미도래 회차는 EOD_ACCELERATED 이지만 가산이자는 붙지 않는다")
    void acceleratedInstallmentsAreNotTreatedAsOverdue() {
      LoanChargeResult r = run();

      InstallmentCharge c = at(r, 7);
      assertThat(c.getStatus()).isEqualTo(InstallmentStatus.EOD_ACCELERATED);
      assertThat(c.getDefaultInterestAccrued()).isZero();
      // 조기 변제기가 도래했으므로 약정이자는 EOD일부터 붙는다
      assertThat(c.getOrdinaryInterestAccrued()).isPositive();
      assertThat(c.getApplicableAnnualRate()).isEqualTo(AnnualRate.ofPercent(18));
    }

    @Test
    @DisplayName("총 가산이자는 4~6회차에서만 발생한다")
    void totalDefaultInterestComesOnlyFromOverdueInstallments() {
      LoanChargeResult r = run();

      long fromOverdue = at(r, 4).getDefaultInterestAccrued()
          + at(r, 5).getDefaultInterestAccrued()
          + at(r, 6).getDefaultInterestAccrued();

      assertThat(r.getTotalDefaultInterestAccrued()).isEqualTo(fromOverdue).isPositive();
    }

    @Test
    @DisplayName("연체 회차 이자는 약정분 + 가산분 = 연 20% 이며 38%로 중복 합산되지 않는다")
    void overdueInterestIsNotDoubleCounted() {
      LoanChargeResult r = run();
      InstallmentCharge c = at(r, 4);

      long base = c.getOverduePrincipal();
      long days = c.getOverdueDays();
      InterestCalculationOptions opt = InterestCalculationOptions.defaults();

      long at20 = PersonalLoanInterestCalculator.interest(
          base, AnnualRate.ofPercent(20), days, opt, c.getAccrualFrom(), c.getAccrualTo());
      long at38 = PersonalLoanInterestCalculator.interest(
          base, AnnualRate.ofPercent(38), days, opt, c.getAccrualFrom(), c.getAccrualTo());

      assertThat(c.totalInterestAccrued()).isEqualTo(at20);
      assertThat(c.totalInterestAccrued()).isNotEqualTo(at38);
    }

    @Test
    @DisplayName("감사 로그에 법·정책 근거가 남는다")
    void auditLogsExplainTheRules() {
      LoanChargeResult r = run();
      assertThat(r.getAuditLogs())
          .anyMatch(l -> l.contains("min(약정이율 + 3%p, 20%)"))
          .anyMatch(l -> l.contains("원래 납기일이 도래하지 않은 원금에는 연체가산이자를 부과하지 않음"))
          .anyMatch(l -> l.contains("개인채무자보호법 적용 판정 = 적용"))
          .anyMatch(l -> l.contains("기한이익상실 효력일: 2026-07-20"))
          .anyMatch(l -> l.contains("반올림 정책"));
      // 회차별 근거 (원래 납기일 · 적용이율 · 일수 · 원금)
      assertThat(r.getAuditLogs()).anyMatch(l -> l.startsWith("4회차 | 원래납기일 2026-05-10"));
    }
  }

  // ══════════════════════════════════════════════════════════════
  @Nested
  @DisplayName("테스트 2 — 15% / 4회차만 미납 / 기한이익상실 없음")
  class Test2 {

    private LoanChargeResult run() {
      LoanInterestInput base = personalLoan(30_000_000L, 15.0)
          .asOfDate(LocalDate.of(2026, 7, 10)).build();
      List<ScheduledInstallment> schedule = PersonalLoanInterestCalculator.generateSchedule(base);

      // 4회차(2026-05-10)만 건너뛰고 5·6회차는 납부.
      // 일반 변제충당은 오래된 회차부터 채우므로, 특정 회차만 미납인 상태를 만들려면
      // 회차를 지정해 꽂아야 한다.
      List<LoanPaymentRecord> payments = new java.util.ArrayList<>();
      for (ScheduledInstallment s : schedule) {
        if (s.installmentNo() > 6) break;
        if (s.installmentNo() == 4) continue;
        payments.add(LoanPaymentRecord.forInstallment(
            s.dueDate(), s.installmentNo(), s.scheduledPrincipal(), s.scheduledInterest(), 0));
      }
      return PersonalLoanInterestCalculator.calculateAccruedCharges(
          base.toBuilder().payments(payments).build(), schedule);
    }

    @Test
    @DisplayName("연체이율 = 15% + 3% = 18% (상한 미도달)")
    void defaultRateIsEighteen() {
      assertThat(run().getDefaultRate()).isEqualTo(AnnualRate.ofPercent(18));
    }

    @Test
    @DisplayName("4회차 미납 원금에만 18%가 적용된다")
    void onlyFourthInstallmentAccruesDefaultInterest() {
      LoanChargeResult r = run();

      InstallmentCharge fourth = at(r, 4);
      assertThat(fourth.getStatus()).isEqualTo(InstallmentStatus.DUE_UNPAID);
      assertThat(fourth.getApplicableAnnualRate()).isEqualTo(AnnualRate.ofPercent(18));
      assertThat(fourth.getDefaultInterestAccrued()).isPositive();

      // 그 회차 총이자 = 미납원금 × 18% × 연체일수/365
      long expected = PersonalLoanInterestCalculator.interest(
          fourth.getOverduePrincipal(), AnnualRate.ofPercent(18), fourth.getOverdueDays(),
          InterestCalculationOptions.defaults(), fourth.getAccrualFrom(), fourth.getAccrualTo());
      assertThat(fourth.totalInterestAccrued()).isEqualTo(expected);

      // 나머지 회차에는 가산이자가 없다
      assertThat(r.getTotalDefaultInterestAccrued()).isEqualTo(fourth.getDefaultInterestAccrued());
    }

    @Test
    @DisplayName("기한이익상실이 없으므로 미도래 회차는 FUTURE 이고 발생액이 0이다")
    void futureInstallmentsAccrueNothingWithoutEod() {
      LoanChargeResult r = run();
      for (int no = 7; no <= 24; no++) {
        InstallmentCharge c = at(r, no);
        assertThat(c.getStatus()).as("%d회차", no).isEqualTo(InstallmentStatus.FUTURE);
        assertThat(c.getDefaultInterestAccrued()).isZero();
        assertThat(c.getOrdinaryInterestAccrued()).isZero();
      }
    }
  }

  // ══════════════════════════════════════════════════════════════
  @Nested
  @DisplayName("테스트 3 — 19% 이면 연체이율이 20%로 잘린다")
  class Test3 {

    @Test
    @DisplayName("min(19% + 3%, 20%) = 20%")
    void defaultRateNeverExceedsTwenty() {
      LoanChargeResult r = PersonalLoanInterestCalculator.calculate(
          personalLoan(30_000_000L, 19.0).asOfDate(LocalDate.of(2026, 6, 10)).build());

      assertThat(r.getCalculatedDefaultRate()).isEqualTo(AnnualRate.ofPercent(22));
      assertThat(r.getDefaultRate()).isEqualTo(AnnualRate.ofPercent(20));
      assertThat(r.getDefaultRate().asPercent()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("약정이율이 20%여도 연체이율은 20%를 넘지 않는다")
    void capHoldsAtTheLegalMaximum() {
      LoanChargeResult r = PersonalLoanInterestCalculator.calculate(
          personalLoan(30_000_000L, 20.0).asOfDate(LocalDate.of(2026, 6, 10)).build());

      assertThat(r.getDefaultRate()).isEqualTo(AnnualRate.ofPercent(20));
      // 가산분이 0이므로 연체해도 20%를 넘지 않는다
      assertThat(r.getDefaultRate().minus(r.getContractRate())).isEqualTo(AnnualRate.ZERO);
    }
  }

  // ══════════════════════════════════════════════════════════════
  @Nested
  @DisplayName("테스트 4 — 6회차 납기일 '전에' EOD 효력 발생")
  class Test4 {

    private LoanChargeResult run() {
      // 6회차 납기일은 2026-07-10. EOD 는 그 전인 2026-06-20.
      // 기준일도 6회차 납기일 전(2026-06-30)이라 6회차는 원래 약정상 미도래다.
      LoanInterestInput base = personalLoan(30_000_000L, 18.0)
          .asOfDate(LocalDate.of(2026, 6, 30))
          .eodEffectiveDate(LocalDate.of(2026, 6, 20))
          .build();
      List<ScheduledInstallment> schedule = PersonalLoanInterestCalculator.generateSchedule(base);

      return PersonalLoanInterestCalculator.calculateAccruedCharges(
          base.toBuilder().payments(paidThrough(schedule, 3)).build(), schedule);
    }

    @Test
    @DisplayName("6회차는 원래 미도래이므로 연체이율이 적용되지 않는다")
    void sixthInstallmentIsNotOverdue() {
      LoanChargeResult r = run();
      InstallmentCharge sixth = at(r, 6);

      assertThat(sixth.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 10));
      assertThat(sixth.getStatus()).isEqualTo(InstallmentStatus.EOD_ACCELERATED);
      assertThat(sixth.getDefaultInterestAccrued()).isZero();
      assertThat(sixth.getApplicableAnnualRate()).isEqualTo(AnnualRate.ofPercent(18));
      assertThat(sixth.getOverdueDays()).isZero();
    }

    @Test
    @DisplayName("6회차 이후 모든 미래 회차도 가산이자가 0이다")
    void allLaterInstallmentsAccrueNoDefaultInterest() {
      LoanChargeResult r = run();
      for (int no = 6; no <= 24; no++) {
        assertThat(at(r, no).getDefaultInterestAccrued())
            .as("%d회차 가산이자", no).isZero();
      }
    }

    @Test
    @DisplayName("이미 도래한 4·5회차에는 연체이율이 그대로 적용된다")
    void maturedInstallmentsStillAccrueDefaultInterest() {
      LoanChargeResult r = run();
      for (int no : new int[]{4, 5}) {
        InstallmentCharge c = at(r, no);
        assertThat(c.getStatus()).as("%d회차", no).isEqualTo(InstallmentStatus.DUE_UNPAID);
        assertThat(c.getApplicableAnnualRate()).isEqualTo(AnnualRate.ofPercent(20));
        assertThat(c.getDefaultInterestAccrued()).as("%d회차 가산이자", no).isPositive();
      }
      assertThat(r.getTotalDefaultInterestAccrued()).isEqualTo(
          at(r, 4).getDefaultInterestAccrued() + at(r, 5).getDefaultInterestAccrued());
    }
  }

  // ══════════════════════════════════════════════════════════════
  @Nested
  @DisplayName("보호법 적용 판정과 비적용 채권의 정책")
  class ProtectionScope {

    @Test
    @DisplayName("최초 원금 5,000만원이면 경계값이라 미적용")
    void thresholdIsExclusive() {
      LoanChargeResult r = PersonalLoanInterestCalculator.calculate(
          personalLoan(50_000_000L, 18.0).asOfDate(LocalDate.of(2026, 6, 10)).build());
      assertThat(r.isPersonalDebtorProtectionApplies()).isFalse();
    }

    @Test
    @DisplayName("법인 채무자는 미적용")
    void corporateDebtorIsOutOfScope() {
      LoanChargeResult r = PersonalLoanInterestCalculator.calculate(
          personalLoan(30_000_000L, 18.0)
              .debtorType(DebtorType.CORPORATE)
              .asOfDate(LocalDate.of(2026, 6, 10)).build());
      assertThat(r.isPersonalDebtorProtectionApplies()).isFalse();
    }

    @Test
    @DisplayName("비적용이어도 기본 정책은 미도래 원금에 가산이자를 붙이지 않는다")
    void legacyDefaultPolicyStillProtectsUnmaturedPrincipal() {
      LoanInterestInput base = personalLoan(30_000_000L, 18.0)
          .debtorType(DebtorType.CORPORATE)
          .asOfDate(LocalDate.of(2026, 8, 5))
          .eodEffectiveDate(LocalDate.of(2026, 7, 20))
          .build();
      List<ScheduledInstallment> schedule = PersonalLoanInterestCalculator.generateSchedule(base);
      LoanChargeResult r = PersonalLoanInterestCalculator.calculateAccruedCharges(
          base.toBuilder().payments(paidThrough(schedule, 3)).build(), schedule);

      assertThat(r.isPersonalDebtorProtectionApplies()).isFalse();
      assertThat(at(r, 10).getDefaultInterestAccrued()).isZero();
    }

    @Test
    @DisplayName("회사가 명시적으로 선택하면 비적용 채권은 잔액 전체에 연체이율을 적용한다")
    void legacyPolicyCanChargeEntireBalance() {
      LoanInterestInput base = personalLoan(30_000_000L, 18.0)
          .debtorType(DebtorType.CORPORATE)
          .asOfDate(LocalDate.of(2026, 8, 5))
          .eodEffectiveDate(LocalDate.of(2026, 7, 20))
          .legacyAccelerationPolicy(
              LegacyAccelerationDefaultInterestPolicy.DEFAULT_INTEREST_ON_ENTIRE_BALANCE)
          .build();
      List<ScheduledInstallment> schedule = PersonalLoanInterestCalculator.generateSchedule(base);
      LoanChargeResult r = PersonalLoanInterestCalculator.calculateAccruedCharges(
          base.toBuilder().payments(paidThrough(schedule, 3)).build(), schedule);

      InstallmentCharge c = at(r, 10);
      assertThat(c.getStatus()).isEqualTo(InstallmentStatus.EOD_ACCELERATED);
      assertThat(c.getDefaultInterestAccrued()).isPositive();
      assertThat(c.getApplicableAnnualRate()).isEqualTo(AnnualRate.ofPercent(20));
    }

    @Test
    @DisplayName("개인 보호 대상이면 같은 조건이라도 가산이자가 붙지 않는다")
    void protectionOverridesLegacyPolicy() {
      LoanInterestInput base = personalLoan(30_000_000L, 18.0)
          .asOfDate(LocalDate.of(2026, 8, 5))
          .eodEffectiveDate(LocalDate.of(2026, 7, 20))
          .legacyAccelerationPolicy(
              LegacyAccelerationDefaultInterestPolicy.DEFAULT_INTEREST_ON_ENTIRE_BALANCE)
          .build();
      List<ScheduledInstallment> schedule = PersonalLoanInterestCalculator.generateSchedule(base);
      LoanChargeResult r = PersonalLoanInterestCalculator.calculateAccruedCharges(
          base.toBuilder().payments(paidThrough(schedule, 3)).build(), schedule);

      assertThat(r.isPersonalDebtorProtectionApplies()).isTrue();
      assertThat(at(r, 10).getDefaultInterestAccrued()).isZero();
    }
  }

  // ══════════════════════════════════════════════════════════════
  @Nested
  @DisplayName("스케줄·일수·반올림")
  class ScheduleAndDays {

    @Test
    @DisplayName("원리금균등: 원금 합계가 대출금과 정확히 일치하고 잔여원금이 0으로 끝난다")
    void scheduleReconciles() {
      List<ScheduledInstallment> s = PersonalLoanInterestCalculator.generateSchedule(
          personalLoan(30_000_000L, 18.0).asOfDate(LocalDate.of(2026, 2, 10)).build());

      assertThat(s).hasSize(24);
      assertThat(s.stream().mapToLong(ScheduledInstallment::scheduledPrincipal).sum())
          .isEqualTo(30_000_000L);
      assertThat(s.get(23).closingPrincipal()).isZero();
      // 1회차 이자 = 3,000만 × 18% / 12
      assertThat(s.get(0).scheduledInterest()).isEqualTo(450_000L);
    }

    @Test
    @DisplayName("납기일 당일 납부는 연체일수 0일이고, 다음날부터 1일씩 센다")
    void overdueDaysStartTheDayAfterDueDate() {
      LocalDate due = LocalDate.of(2026, 5, 10);
      assertThat(PersonalLoanInterestCalculator.daysBetween(due, due)).isZero();
      assertThat(PersonalLoanInterestCalculator.daysBetween(due, due.plusDays(1))).isEqualTo(1);
      assertThat(PersonalLoanInterestCalculator.daysBetween(due, due.minusDays(5))).isZero();
    }

    @Test
    @DisplayName("기본 반올림은 일자별 절사가 아니라 회차 단위 최종 반올림이다")
    void defaultRoundingIsFinalNotPerDiem() {
      long principal = 1_000_000L;
      AnnualRate rate = AnnualRate.ofPercent(18);
      LocalDate from = LocalDate.of(2026, 5, 10);
      LocalDate to = from.plusDays(30);

      long finalRound = PersonalLoanInterestCalculator.interest(
          principal, rate, 30, InterestCalculationOptions.defaults(), from, to);
      long perDiemFloor = PersonalLoanInterestCalculator.interest(
          principal, rate, 30,
          new InterestCalculationOptions(InterestCalculationOptions.DayCount.FIXED_365,
              InterestCalculationOptions.Rounding.PER_DIEM_FLOOR), from, to);

      // 1,000,000 × 0.18 × 30 / 365 = 14,794.52 -> 14,795
      assertThat(finalRound).isEqualTo(14_795L);
      // 하루치 493.15 를 절사하면 493 × 30 = 14,790 으로 5원이 샌다
      assertThat(perDiemFloor).isEqualTo(14_790L);
      assertThat(finalRound).isGreaterThan(perDiemFloor);
    }

    @Test
    @DisplayName("윤년 옵션을 켜면 2월 29일이 낀 기간은 366일로 계산한다")
    void leapYearOptionUses366() {
      long principal = 10_000_000L;
      AnnualRate rate = AnnualRate.ofPercent(18);
      LocalDate from = LocalDate.of(2028, 2, 1);   // 2028 은 윤년
      LocalDate to = LocalDate.of(2028, 3, 31);

      long fixed = PersonalLoanInterestCalculator.interest(
          principal, rate, 59, InterestCalculationOptions.defaults(), from, to);
      long actual = PersonalLoanInterestCalculator.interest(
          principal, rate, 59,
          new InterestCalculationOptions(InterestCalculationOptions.DayCount.ACTUAL_365_366,
              InterestCalculationOptions.Rounding.FINAL_HALF_UP), from, to);

      assertThat(actual).isLessThan(fixed);
    }

    @Test
    @DisplayName("이율은 퍼센트·소수 어느 쪽으로 만들어도 같은 값이다")
    void rateUnitsAreInterchangeable() {
      assertThat(AnnualRate.ofPercent(18)).isEqualTo(AnnualRate.ofFraction(0.18));
      assertThat(AnnualRate.ofPercent(18).asFraction()).isEqualByComparingTo("0.18");
      assertThat(AnnualRate.ofFraction(0.18).asPercent()).isEqualByComparingTo("18.00");
      assertThat(AnnualRate.ofPercent(18)).hasToString("연 18%");
    }

    @Test
    @DisplayName("총 청구액 = 미납원금 + 발생 약정이자 + 발생 가산이자")
    void totalPayableReconciles() {
      LoanInterestInput base = personalLoan(30_000_000L, 18.0)
          .asOfDate(LocalDate.of(2026, 8, 5))
          .eodEffectiveDate(LocalDate.of(2026, 7, 20))
          .build();
      List<ScheduledInstallment> schedule = PersonalLoanInterestCalculator.generateSchedule(base);
      LoanChargeResult r = PersonalLoanInterestCalculator.calculateAccruedCharges(
          base.toBuilder().payments(paidThrough(schedule, 3)).build(), schedule);

      assertThat(r.getTotalPayable()).isEqualTo(
          r.getRemainingPrincipal()
              + r.getTotalOrdinaryInterestAccrued()
              + r.getTotalDefaultInterestAccrued());

      assertThat(r.getRemainingPrincipal()).isEqualTo(
          r.getInstallments().stream().mapToLong(InstallmentCharge::getOverduePrincipal).sum());
    }
  }
}
