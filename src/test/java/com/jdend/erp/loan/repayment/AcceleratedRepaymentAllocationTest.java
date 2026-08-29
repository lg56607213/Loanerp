package com.jdend.erp.loan.repayment;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.loan.policy.AcceleratedRepaymentPolicy;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기한이익상실 이후 상환 처리.
 *
 * 있던 문제: 상실을 등록하면 미래 회차가 '청구중지'로 바뀌는데, 변제충당이
 * 청구중지 회차를 통째로 제외해 <b>잔여원금 전액을 청구해 놓고 갚을 수가 없었다.</b>
 * 입금이 도래 회차만 메우고 나머지는 전부 선수금으로 남았다.
 */
class AcceleratedRepaymentAllocationTest {

  private final RepaymentAllocator allocator = new RepaymentAllocator();

  private Contract contract;
  private List<PaymentSchedule> schedules;

  @BeforeEach
  void setUp() {
    contract = Contract.builder()
        .contractNumber("L-EOD-0001")
        .customerNumber("C-0001")
        .status(ContractStatus.ACCELERATED)
        .loanAmount(30_000_000L)
        .customerType("개인")
        .interestRate(new BigDecimal("18.00"))
        .overdueRate(new BigDecimal("20.00"))
        .overdueChargeYn(Boolean.FALSE)   // 지연배상금을 빼고 원금 흐름만 본다
        .build();

    // 1·2회차: 납기일 지난 미납.  3·4회차: 기한이익상실로 청구중지.
    schedules = new ArrayList<>();
    schedules.add(line(1, LocalDate.of(2026, 5, 10), 1_000_000L, 100_000L, PaymentSchedule.LINE_UNPAID));
    schedules.add(line(2, LocalDate.of(2026, 6, 10), 1_000_000L, 100_000L, PaymentSchedule.LINE_UNPAID));
    schedules.add(line(3, LocalDate.of(2026, 8, 10), 1_000_000L, 100_000L, PaymentSchedule.LINE_SUSPENDED));
    schedules.add(line(4, LocalDate.of(2026, 9, 10), 1_000_000L, 100_000L, PaymentSchedule.LINE_SUSPENDED));
  }

  private PaymentSchedule line(int no, LocalDate due, long principal, long interest, String status) {
    return PaymentSchedule.builder()
        .id((long) no).contractNumber("L-EOD-0001").installmentNo(no).paymentDate(due)
        .principalAmount(principal).interestAmount(interest)
        .paidPrincipal(0L).paidInterest(0L).paidOverdueInterest(0L).paidCost(0L)
        .lineStatus(status)
        .build();
  }

  @Test
  @DisplayName("기본 정책: 청구중지 회차의 원금도 갚을 수 있다")
  void acceleratedPrincipalCanBeRepaidByDefault() {
    // 도래 회차 2건(원금 200만 + 이자 20만 = 220만)을 넘는 300만원 입금
    RepaymentAllocation alloc = allocator.allocate(
        contract, schedules, 3_000_000L, LocalDate.of(2026, 7, 10), 0L, false);

    assertThat(alloc.getInterest()).isEqualTo(200_000L);
    // 도래 회차 원금 200만 + 청구중지 회차 원금 80만
    assertThat(alloc.getPrincipal()).isEqualTo(2_800_000L);
    assertThat(alloc.getExcess()).isZero();

    assertThat(schedules.get(2).getPaidPrincipal()).isEqualTo(800_000L);
    // 청구중지 상태는 그대로 유지된다 (청구를 재개하는 것이 아니다)
    assertThat(schedules.get(2).getLineStatus()).isEqualTo(PaymentSchedule.LINE_SUSPENDED);
  }

  @Test
  @DisplayName("청구중지 회차의 약정이자는 충당하지 않는다 — 조기 상환된 구간이라 발생하지 않았다")
  void suspendedInstallmentInterestIsNotCharged() {
    RepaymentAllocation alloc = allocator.allocate(
        contract, schedules, 3_000_000L, LocalDate.of(2026, 7, 10), 0L, false);

    // 이자는 도래한 1·2회차분(각 10만)만
    assertThat(alloc.getInterest()).isEqualTo(200_000L);
    assertThat(schedules.get(2).getPaidInterest()).isZero();
    assertThat(schedules.get(3).getPaidInterest()).isZero();
  }

  @Test
  @DisplayName("선수금 정책: 도래 회차만 충당하고 나머지는 선수금으로 남는다")
  void holdAsPrepaidLeavesRemainderAsExcess() {
    RepaymentAllocation alloc = allocator.allocate(
        contract, schedules, 3_000_000L, LocalDate.of(2026, 7, 10), 0L, false,
        AcceleratedRepaymentPolicy.HOLD_AS_PREPAID);

    assertThat(alloc.getInterest()).isEqualTo(200_000L);
    assertThat(alloc.getPrincipal()).isEqualTo(2_000_000L);
    // 300만 − 220만 = 80만이 선수금으로 남는다 (수정 전의 동작)
    assertThat(alloc.getExcess()).isEqualTo(800_000L);
    assertThat(schedules.get(2).getPaidPrincipal()).isZero();
  }

  @Test
  @DisplayName("어느 정책이든 입금액과 충당액은 어긋나지 않는다")
  void allocationReconcilesUnderBothPolicies() {
    for (AcceleratedRepaymentPolicy policy : AcceleratedRepaymentPolicy.values()) {
      setUp();
      RepaymentAllocation alloc = allocator.allocate(
          contract, schedules, 3_000_000L, LocalDate.of(2026, 7, 10), 0L, false, policy);
      assertThat(alloc.allocatedTotal() + alloc.getExcess())
          .as("%s", policy).isEqualTo(3_000_000L);
    }
  }

  @Test
  @DisplayName("기한이익상실이 없는 평범한 채권은 동작이 바뀌지 않는다")
  void normalContractIsUnaffected() {
    for (PaymentSchedule ps : schedules) ps.setLineStatus(PaymentSchedule.LINE_UNPAID);
    contract.setStatus(ContractStatus.OVERDUE);

    RepaymentAllocation alloc = allocator.allocate(
        contract, schedules, 3_000_000L, LocalDate.of(2026, 7, 10), 0L, false);

    // 도래한 1·2회차 이자·원금을 채우고, 남은 80만은 미도래 회차 원금으로 앞당겨 충당된다
    assertThat(alloc.getInterest()).isEqualTo(200_000L);
    assertThat(alloc.getPrincipal()).isEqualTo(2_800_000L);
    assertThat(alloc.getExcess()).isZero();
  }
}
