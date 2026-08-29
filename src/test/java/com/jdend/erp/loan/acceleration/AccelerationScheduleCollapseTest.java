package com.jdend.erp.loan.acceleration;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.loan.repayment.RepaymentAllocation;
import com.jdend.erp.loan.repayment.RepaymentAllocator;
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
 * 기한이익상실로 스케줄을 접었을 때의 동작.
 *
 * 등록 자체는 DB가 있어야 돌아가므로, 여기서는 접은 결과 스케줄이
 * 이자·연체·충당에서 어떻게 다뤄지는지를 검증한다.
 *
 * 접은 회차('일시청구')가 지켜야 할 것
 *   - 원금은 갚을 수 있다 (조기 변제기가 도래한 원금이다)
 *   - 약정이자가 붙지 않는다 (조기 상환된 구간의 이자는 발생하지 않았다)
 *   - 지연배상금이 붙지 않는다 (개인채무자보호법 — 원래 납기일 미도래)
 */
class AccelerationScheduleCollapseTest {

  private final RepaymentAllocator allocator = new RepaymentAllocator();

  private Contract contract;
  private List<PaymentSchedule> schedules;

  /** 상실일 2026-07-20. 1·2회차는 도래 미납, 3~24회차는 '일시청구' 1건으로 접힌 상태. */
  @BeforeEach
  void setUp() {
    contract = Contract.builder()
        .contractNumber("L-EOD-0002")
        .customerNumber("C-0001")
        .customerType("개인")
        .debtType("개인금융채권")
        .loanAmount(30_000_000L)
        .status(ContractStatus.ACCELERATED)
        .repaymentMethod("만기일시")          // 상실과 함께 전환된다
        .interestRate(new BigDecimal("18.00"))
        .overdueRate(new BigDecimal("20.00"))
        .overdueChargeYn(Boolean.TRUE)
        .build();

    schedules = new ArrayList<>();
    schedules.add(line(1, LocalDate.of(2026, 5, 10), 1_000_000L, 150_000L, PaymentSchedule.LINE_UNPAID));
    schedules.add(line(2, LocalDate.of(2026, 6, 10), 1_000_000L, 150_000L, PaymentSchedule.LINE_UNPAID));
    // 접은 회차: 3~24회차 원금 합계, 이자 0, 납기일 = 상실일
    schedules.add(line(3, LocalDate.of(2026, 7, 20), 22_000_000L, 0L, PaymentSchedule.LINE_CALLED));
  }

  private PaymentSchedule line(int no, LocalDate due, long principal, long interest, String status) {
    return PaymentSchedule.builder()
        .id((long) no).contractNumber("L-EOD-0002").installmentNo(no).paymentDate(due)
        .principalAmount(principal).interestAmount(interest)
        .paidPrincipal(0L).paidInterest(0L).paidOverdueInterest(0L).paidCost(0L)
        .lineStatus(status)
        .build();
  }

  @Test
  @DisplayName("일시청구 회차는 기한이익상실 회차로 인식된다")
  void calledLineIsRecognisedAsAccelerated() {
    assertThat(schedules.get(2).isAcceleratedLine()).isTrue();
    assertThat(schedules.get(0).isAcceleratedLine()).isFalse();
  }

  @Test
  @DisplayName("일시청구 회차의 원금은 갚을 수 있다")
  void calledPrincipalCanBeRepaid() {
    // 도래 회차 2건(원금 200만 + 이자 30만)을 넘는 500만원 입금
    RepaymentAllocation alloc = allocator.allocate(
        contract, schedules, 5_000_000L, LocalDate.of(2026, 8, 20), 0L, false);

    assertThat(alloc.getInterest()).isEqualTo(300_000L);
    // 도래 회차 원금 200만 + 일시청구 회차 원금 (나머지)
    assertThat(alloc.getPrincipal()).isPositive();
    assertThat(schedules.get(2).getPaidPrincipal()).isPositive();
    assertThat(alloc.getExcess()).isZero();
  }

  @Test
  @DisplayName("일시청구 회차에는 약정이자도 지연배상금도 붙지 않는다")
  void calledLineAccruesNoInterest() {
    RepaymentAllocation alloc = allocator.allocate(
        contract, schedules, 5_000_000L, LocalDate.of(2026, 8, 20), 0L, false);

    PaymentSchedule called = schedules.get(2);
    assertThat(called.getInterestAmount()).isZero();
    assertThat(called.getPaidInterest()).isZero();
    assertThat(called.getPaidOverdueInterest()).isZero();

    // 지연배상금은 원래 납기일이 지난 1·2회차에서만 나온다
    assertThat(alloc.getOverdueInterest()).isEqualTo(
        schedules.get(0).getPaidOverdueInterest() + schedules.get(1).getPaidOverdueInterest());
  }

  @Test
  @DisplayName("원금을 받아도 일시청구 상태는 유지된다")
  void calledStatusSurvivesRepayment() {
    allocator.allocate(contract, schedules, 5_000_000L, LocalDate.of(2026, 8, 20), 0L, false);
    assertThat(schedules.get(2).getLineStatus()).isEqualTo(PaymentSchedule.LINE_CALLED);
  }

  @Test
  @DisplayName("접어도 미납 원금 합계는 변하지 않는다")
  void collapsingPreservesOutstandingPrincipal() {
    // 접기 전 3~24회차 원금 합계가 2,200만이었다는 전제
    long outstanding = schedules.stream().mapToLong(PaymentSchedule::unpaidPrincipal).sum();
    assertThat(outstanding).isEqualTo(24_000_000L);   // 100만 + 100만 + 2,200만
  }

  @Test
  @DisplayName("입금이 커도 충당액과 입금액은 어긋나지 않는다")
  void allocationReconciles() {
    RepaymentAllocation alloc = allocator.allocate(
        contract, schedules, 30_000_000L, LocalDate.of(2026, 8, 20), 0L, false);
    assertThat(alloc.allocatedTotal() + alloc.getExcess()).isEqualTo(30_000_000L);
  }
}
