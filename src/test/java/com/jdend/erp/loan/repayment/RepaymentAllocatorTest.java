package com.jdend.erp.loan.repayment;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 변제충당 순서 검증.
 *
 * DB나 스프링 컨텍스트 없이 도는 단위 테스트다. RepaymentAllocator는 순수 계산기라
 * 이렇게 직접 세워 확인할 수 있다.
 *
 * 확정된 정책(설계서 §5.3):
 *   일반 채권 — 법적비용 → 지연배상금 → 약정이자 → 원금
 *   상각 채권 — 법적비용 → 원금 → 지연배상금 → 약정이자
 */
class RepaymentAllocatorTest {

  private final RepaymentAllocator allocator = new RepaymentAllocator();

  /** 연체이자를 부과하지 않는 계약. 지연배상금이 끼지 않아 순서 확인이 명확해진다. */
  private Contract contract(String status) {
    return Contract.builder()
        .contractNumber("L-TEST-0001")
        .customerNumber("C-0001")
        .status(status)
        .interestRate(new BigDecimal("20.00"))
        .overdueRate(new BigDecimal("20.00"))
        .overdueChargeYn(Boolean.FALSE)
        .build();
  }

  /** 이미 도래한(연체된) 회차 하나. */
  private List<PaymentSchedule> oneDueInstallment(long principal, long interest, LocalDate dueDate) {
    PaymentSchedule ps = PaymentSchedule.builder()
        .id(1L)
        .contractNumber("L-TEST-0001")
        .installmentNo(1)
        .paymentDate(dueDate)
        .principalAmount(principal)
        .interestAmount(interest)
        .paidPrincipal(0L)
        .paidInterest(0L)
        .paidOverdueInterest(0L)
        .paidCost(0L)
        .lineStatus(PaymentSchedule.LINE_UNPAID)
        .build();
    List<PaymentSchedule> list = new ArrayList<>();
    list.add(ps);
    return list;
  }

  @Test
  @DisplayName("일반 채권: 법적비용이 원금·이자보다 먼저 충당된다")
  void legalCostIsAllocatedFirstForNormalContract() {
    Contract c = contract(ContractStatus.OVERDUE);
    List<PaymentSchedule> schedules = oneDueInstallment(800_000L, 200_000L, LocalDate.of(2026, 7, 10));

    RepaymentAllocation alloc = allocator.allocate(
        c, schedules, 1_000_000L, LocalDate.of(2026, 8, 24), 180_000L);

    assertThat(alloc.getCost()).isEqualTo(180_000L);
    assertThat(alloc.getInterest()).isEqualTo(200_000L);
    assertThat(alloc.getPrincipal()).isEqualTo(620_000L);
    assertThat(alloc.getExcess()).isZero();
    assertThat(alloc.allocatedTotal()).isEqualTo(1_000_000L);
  }

  @Test
  @DisplayName("법적비용 회수 실적은 회차의 paidCost에 남고, 완납 판정에는 끼지 않는다")
  void legalCostRecoveryIsRecordedOnScheduleWithoutAffectingLineStatus() {
    Contract c = contract(ContractStatus.OVERDUE);
    List<PaymentSchedule> schedules = oneDueInstallment(800_000L, 200_000L, LocalDate.of(2026, 7, 10));

    allocator.allocate(c, schedules, 1_000_000L, LocalDate.of(2026, 8, 24), 180_000L);

    PaymentSchedule ps = schedules.get(0);
    assertThat(ps.getPaidCost()).isEqualTo(180_000L);
    // 청구액 100만 중 18만이 법적비용으로 빠졌으니 원금·이자는 82만만 채워졌다 → 부분납
    assertThat(ps.paidTotal()).isEqualTo(820_000L);
    assertThat(ps.getLineStatus()).isEqualTo(PaymentSchedule.LINE_PARTIAL);
  }

  @Test
  @DisplayName("법적비용이 없으면 1순위 단계를 그냥 지나간다")
  void noLegalCostMeansStepIsSkipped() {
    Contract c = contract(ContractStatus.OVERDUE);
    List<PaymentSchedule> schedules = oneDueInstallment(800_000L, 200_000L, LocalDate.of(2026, 7, 10));

    RepaymentAllocation alloc = allocator.allocate(
        c, schedules, 1_000_000L, LocalDate.of(2026, 8, 24), 0L);

    assertThat(alloc.getCost()).isZero();
    assertThat(alloc.getInterest()).isEqualTo(200_000L);
    assertThat(alloc.getPrincipal()).isEqualTo(800_000L);
    assertThat(schedules.get(0).getLineStatus()).isEqualTo(PaymentSchedule.LINE_PAID);
  }

  @Test
  @DisplayName("상각 채권: 법적비용 다음에 이자가 아니라 원금이 먼저 충당된다")
  void writtenOffContractAllocatesPrincipalBeforeInterest() {
    Contract c = contract(ContractStatus.WRITTEN_OFF);
    List<PaymentSchedule> schedules = oneDueInstallment(800_000L, 200_000L, LocalDate.of(2026, 7, 10));

    // 법적비용 18만 + 원금 80만 = 98만. 입금 90만이면 원금을 다 못 채우고 이자까지 못 간다.
    RepaymentAllocation alloc = allocator.allocate(
        c, schedules, 900_000L, LocalDate.of(2026, 8, 24), 180_000L);

    assertThat(alloc.getCost()).isEqualTo(180_000L);
    assertThat(alloc.getPrincipal()).isEqualTo(720_000L);
    assertThat(alloc.getInterest()).isZero();
  }

  @Test
  @DisplayName("이자 단계에서는 지연배상금을 약정이자보다 먼저 충당한다")
  void overdueInterestIsAllocatedBeforeContractualInterest() {
    Contract c = Contract.builder()
        .contractNumber("L-TEST-0001")
        .customerNumber("C-0001")
        .status(ContractStatus.OVERDUE)
        .interestRate(new BigDecimal("20.00"))
        .overdueRate(new BigDecimal("20.00"))
        .overdueChargeYn(Boolean.TRUE)   // 연체이자 부과
        .build();
    List<PaymentSchedule> schedules = oneDueInstallment(800_000L, 200_000L, LocalDate.of(2026, 7, 10));

    // 45일 연체. 지연배상금 = 100만 × 20% ÷ 365 × 45
    long expectedOverdue = 1_000_000L * 20 / 100 * 45 / 365;

    RepaymentAllocation alloc = allocator.allocate(
        c, schedules, 100_000L, LocalDate.of(2026, 8, 24), 0L);

    // 10만은 지연배상금을 덮고 남는 만큼만 약정이자로 간다
    assertThat(alloc.getOverdueInterest()).isEqualTo(expectedOverdue);
    assertThat(alloc.getInterest()).isEqualTo(100_000L - expectedOverdue);
    assertThat(alloc.getPrincipal()).isZero();
  }

  @Test
  @DisplayName("연체이자 미부과 계약은 지연배상금을 충당하지 않는다")
  void overdueInterestIsSkippedWhenNotCharged() {
    Contract c = contract(ContractStatus.OVERDUE);   // overdueChargeYn = false
    List<PaymentSchedule> schedules = oneDueInstallment(800_000L, 200_000L, LocalDate.of(2026, 7, 10));

    RepaymentAllocation alloc = allocator.allocate(
        c, schedules, 100_000L, LocalDate.of(2026, 8, 24), 0L);

    assertThat(alloc.getOverdueInterest()).isZero();
    assertThat(alloc.getInterest()).isEqualTo(100_000L);
  }

  @Test
  @DisplayName("전 회차가 청구중지여도 법적비용 회수 실적은 기록된다")
  void legalCostIsStillRecordedWhenEveryInstallmentIsSuspended() {
    Contract c = contract(ContractStatus.ACCELERATED);
    List<PaymentSchedule> schedules = oneDueInstallment(800_000L, 200_000L, LocalDate.of(2026, 7, 10));
    schedules.get(0).setLineStatus(PaymentSchedule.LINE_SUSPENDED);

    RepaymentAllocation alloc = allocator.allocate(
        c, schedules, 200_000L, LocalDate.of(2026, 8, 24), 180_000L);

    assertThat(alloc.getCost()).isEqualTo(180_000L);
    // 기록할 곳이 없다고 금액만 차감하면 재계산 때 같은 비용을 두 번 충당한다
    assertThat(schedules.get(0).getPaidCost()).isEqualTo(180_000L);
    // 청구중지 회차는 충당 대상이 아니므로 나머지는 선수금으로 남는다
    assertThat(alloc.getExcess()).isEqualTo(20_000L);
    assertThat(schedules.get(0).getLineStatus()).isEqualTo(PaymentSchedule.LINE_SUSPENDED);
  }

  @Test
  @DisplayName("입금이 미회수 법적비용보다 적으면 전액이 법적비용으로만 충당된다")
  void paymentSmallerThanLegalCostGoesEntirelyToCost() {
    Contract c = contract(ContractStatus.OVERDUE);
    List<PaymentSchedule> schedules = oneDueInstallment(800_000L, 200_000L, LocalDate.of(2026, 7, 10));

    RepaymentAllocation alloc = allocator.allocate(
        c, schedules, 50_000L, LocalDate.of(2026, 8, 24), 180_000L);

    assertThat(alloc.getCost()).isEqualTo(50_000L);
    assertThat(alloc.getInterest()).isZero();
    assertThat(alloc.getPrincipal()).isZero();
    assertThat(alloc.getExcess()).isZero();
  }
}
