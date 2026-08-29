package com.jdend.erp.loan.interest;

import com.jdend.erp.loan.policy.DebtType;
import com.jdend.erp.loan.policy.DebtorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 테스트 1 시나리오의 실제 산출값을 표로 찍어 눈으로 확인한다.
 * 검증은 {@link PersonalLoanInterestCalculatorTest} 가 하고, 여기는 설명용이다.
 */
class PersonalLoanInterestScenarioPrintTest {

  @Test
  @DisplayName("3,000만 / 18% / 24개월 · 4~6회차 미납 · EOD 2026-07-20 · 기준일 2026-08-05")
  void printScenario() {
    LoanInterestInput base = LoanInterestInput.builder()
        .originalPrincipal(30_000_000L)
        .contractRate(AnnualRate.ofPercent(18))
        .termMonths(24)
        .startDate(LocalDate.of(2026, 1, 10))
        .firstDueDate(LocalDate.of(2026, 2, 10))
        .debtorType(DebtorType.INDIVIDUAL)
        .debtType(DebtType.PERSONAL_FINANCIAL_CLAIM)
        .asOfDate(LocalDate.of(2026, 8, 5))
        .build();

    List<ScheduledInstallment> schedule = PersonalLoanInterestCalculator.generateSchedule(base);
    List<LoanPaymentRecord> payments = schedule.stream()
        .filter(s -> s.installmentNo() <= 3)
        .map(s -> LoanPaymentRecord.of(s.dueDate(), s.scheduledPayment()))
        .toList();

    LoanChargeResult r = PersonalLoanInterestCalculator.calculateAccruedCharges(
        base.toBuilder().payments(payments).eodEffectiveDate(LocalDate.of(2026, 7, 20)).build(),
        schedule);

    StringBuilder sb = new StringBuilder();
    sb.append("\n약정이율 ").append(r.getContractRate())
      .append(" / 산출 연체이율 ").append(r.getCalculatedDefaultRate())
      .append(" / 적용 연체이율 ").append(r.getDefaultRate())
      .append(" / 보호법 ").append(r.isPersonalDebtorProtectionApplies() ? "적용" : "미적용").append('\n');
    sb.append(String.format("%4s %-12s %-16s %8s %14s %6s %12s %12s%n",
        "회차", "원래납기일", "상태", "적용이율", "미납원금", "일수", "약정이자", "가산이자"));

    for (InstallmentCharge c : r.getInstallments()) {
      if (c.getInstallmentNo() > 9 && c.getInstallmentNo() < 24) continue;
      sb.append(String.format("%4d %-12s %-16s %8s %,14d %6d %,12d %,12d%n",
          c.getInstallmentNo(), c.getDueDate(), c.getStatus(),
          c.getApplicableAnnualRate().asPercent().stripTrailingZeros().toPlainString() + "%",
          c.getOverduePrincipal(), c.getOverdueDays(),
          c.getOrdinaryInterestAccrued(), c.getDefaultInterestAccrued()));
      if (c.getInstallmentNo() == 9) sb.append("  ... (10~23회차 생략, 모두 가산이자 0)\n");
    }

    sb.append(String.format("%n미납원금 합계        %,14d원%n", r.getRemainingPrincipal()));
    sb.append(String.format("발생 약정이자 합계    %,14d원%n", r.getTotalOrdinaryInterestAccrued()));
    sb.append(String.format("발생 가산이자 합계    %,14d원  <- 4~6회차에서만 발생%n",
        r.getTotalDefaultInterestAccrued()));
    sb.append(String.format("총 청구액            %,14d원%n", r.getTotalPayable()));

    System.out.println(sb);

    // 표를 찍는 것과 별개로 핵심 불변식은 여기서도 확인한다
    long futureDefault = r.getInstallments().stream()
        .filter(c -> c.getInstallmentNo() >= 7)
        .mapToLong(InstallmentCharge::getDefaultInterestAccrued).sum();
    assertThat(futureDefault).isZero();
  }
}
