package com.jdend.erp.loan.repayment;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.legal.repository.LegalCostItemRepository;
import com.jdend.erp.loan.writeoff.repository.WriteOffRepository;
import com.jdend.erp.payment.payment.entity.Payment;
import com.jdend.erp.payment.payment.repository.PaymentRepository;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 수납 replay 검증.
 *
 * 특히 법적비용의 '발생일' 처리를 고정한다. 수납은 등록·수정·삭제가 되기 때문에
 * 충당을 증분으로 더하고 빼지 않고 매번 처음부터 다시 흘려보내는데(replay),
 * 이때 법적비용을 총액으로 쓰면 나중에 등록한 비용이 그 이전 수납에까지 소급 적용된다.
 * 그러면 이미 완납이던 회차가 부분납으로 되돌아가 없던 지연배상금이 붙는다.
 *
 * 실제 DB로 재현했던 사례를 그대로 테스트로 옮겼다.
 *   원금 1,000만 / 연 20% / 12회 원리금균등 (PMT 926,345)
 *   1회차 수납 2026-02-10, 법적비용 180,000 발생 2026-03-05, 2회차 수납 2026-03-10
 */
class RepaymentPostingServiceTest {

  private static final String CN = "L-TEST-0001";

  private PaymentScheduleRepository scheduleRepo;
  private PaymentRepository paymentRepo;
  private ContractRepository contractRepo;
  private WriteOffRepository writeOffRepo;
  private LegalCostItemRepository legalCostRepo;
  private RepaymentPostingService service;

  private List<PaymentSchedule> schedules;

  @BeforeEach
  void setUp() {
    scheduleRepo = mock(PaymentScheduleRepository.class);
    paymentRepo = mock(PaymentRepository.class);
    contractRepo = mock(ContractRepository.class);
    writeOffRepo = mock(WriteOffRepository.class);
    legalCostRepo = mock(LegalCostItemRepository.class);

    service = new RepaymentPostingService(
        scheduleRepo, paymentRepo, contractRepo, new RepaymentAllocator(), writeOffRepo, legalCostRepo);

    Contract c = Contract.builder()
        .contractNumber(CN)
        .customerNumber("C-0001")
        .status(ContractStatus.NORMAL)
        .interestRate(new BigDecimal("20.00"))
        .overdueRate(new BigDecimal("20.00"))
        .overdueChargeYn(Boolean.TRUE)
        .build();

    // 1·2회차만 있으면 이 시나리오는 충분하다
    schedules = new ArrayList<>();
    schedules.add(installment(1, LocalDate.of(2026, 2, 10), 759_678L, 166_667L));
    schedules.add(installment(2, LocalDate.of(2026, 3, 10), 772_340L, 154_005L));

    when(contractRepo.findByContractNumber(CN)).thenReturn(Optional.of(c));
    when(scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(CN)).thenReturn(schedules);
    when(writeOffRepo.findFirstByContractNumberOrderByIdDesc(CN)).thenReturn(Optional.empty());
    when(paymentRepo.findByContractNumberOrderByPaymentDateAscIdAsc(CN)).thenReturn(List.of(
        payment(1L, LocalDate.of(2026, 2, 10), 926_345L),
        payment(2L, LocalDate.of(2026, 3, 10), 926_345L)
    ));
  }

  private PaymentSchedule installment(int no, LocalDate due, long principal, long interest) {
    return PaymentSchedule.builder()
        .id((long) no).contractNumber(CN).installmentNo(no).paymentDate(due)
        .principalAmount(principal).interestAmount(interest)
        .paidPrincipal(0L).paidInterest(0L).paidOverdueInterest(0L).paidCost(0L)
        .lineStatus(PaymentSchedule.LINE_UNPAID)
        .build();
  }

  private Payment payment(long id, LocalDate date, long amount) {
    return Payment.builder().id(id).contractNumber(CN).paymentDate(date).paymentAmount(amount).build();
  }

  /** 법적비용이 2026-03-05 에 발생했다고 응답하는 목 */
  private void legalCostIncurredOn(LocalDate incurred, long amount) {
    when(legalCostRepo.sumChargeableByContractNumberAsOf(eq(CN), any(LocalDate.class)))
        .thenAnswer(inv -> {
          LocalDate asOf = inv.getArgument(1);
          return asOf.isBefore(incurred) ? 0L : amount;
        });
  }

  @Test
  @DisplayName("법적비용 발생 전에 받은 수납에는 법적비용이 소급 충당되지 않는다")
  void legalCostIsNotAppliedRetroactivelyToEarlierPayments() {
    legalCostIncurredOn(LocalDate.of(2026, 3, 5), 180_000L);

    service.recompute(CN);

    PaymentSchedule first = schedules.get(0);
    PaymentSchedule second = schedules.get(1);

    // 1회차는 법적비용 발생 전이라 그대로 완납이어야 한다
    assertThat(first.getPaidPrincipal()).isEqualTo(759_678L);
    assertThat(first.getPaidInterest()).isEqualTo(166_667L);
    assertThat(first.getLineStatus()).isEqualTo(PaymentSchedule.LINE_PAID);

    // 소급 충당이 있었다면 1회차가 부분납으로 되돌아가 지연배상금이 붙었을 것이다
    assertThat(first.getPaidOverdueInterest()).isZero();

    // 법적비용은 2회차 수납에서 1순위로 빠진다 (실적은 가장 오래된 회차에 모아 기록)
    assertThat(first.getPaidCost()).isEqualTo(180_000L);
    assertThat(second.getPaidInterest()).isEqualTo(154_005L);
    assertThat(second.getPaidPrincipal()).isEqualTo(926_345L - 180_000L - 154_005L);
    assertThat(second.getLineStatus()).isEqualTo(PaymentSchedule.LINE_PARTIAL);
  }

  @Test
  @DisplayName("입금 총액과 충당 총액이 어긋나지 않는다")
  void allocationReconcilesWithTotalPayments() {
    legalCostIncurredOn(LocalDate.of(2026, 3, 5), 180_000L);

    service.recompute(CN);

    long allocated = schedules.stream()
        .mapToLong(ps -> nz(ps.getPaidPrincipal()) + nz(ps.getPaidInterest())
                       + nz(ps.getPaidOverdueInterest()) + nz(ps.getPaidCost()))
        .sum();
    assertThat(allocated).isEqualTo(926_345L * 2);
  }

  @Test
  @DisplayName("법적비용이 첫 수납보다 앞서 발생했으면 첫 수납부터 충당되고, 그 탓에 생긴 연체는 정당하다")
  void legalCostIncurredBeforeFirstPaymentIsAllocatedFromTheStart() {
    legalCostIncurredOn(LocalDate.of(2026, 1, 20), 180_000L);

    service.recompute(CN);

    PaymentSchedule first = schedules.get(0);
    PaymentSchedule second = schedules.get(1);

    // 1회차 수납에서 법적비용이 먼저 빠져 원금이 180,000 모자랐고,
    // 그 미납분이 28일(2/10 → 3/10) 연체돼 지연배상금이 붙었다.
    // 앞 테스트와 달리 이건 소급이 아니라 실제로 그 시점에 존재하던 비용이므로 정당한 결과다.
    assertThat(first.getPaidCost()).isEqualTo(180_000L);
    assertThat(first.getPaidOverdueInterest()).isEqualTo(180_000L * 20 / 100 * 28 / 365);

    // 2회차 수납에서 1회차 잔여원금이 채워져 결국 완납이 된다
    assertThat(first.getPaidPrincipal()).isEqualTo(759_678L);
    assertThat(first.getPaidInterest()).isEqualTo(166_667L);
    assertThat(first.getLineStatus()).isEqualTo(PaymentSchedule.LINE_PAID);
    assertThat(second.getLineStatus()).isEqualTo(PaymentSchedule.LINE_PARTIAL);
  }

  @Test
  @DisplayName("법적절차가 없는 채권은 1순위 단계를 그냥 지나간다")
  void contractWithoutLegalCaseIsUnaffected() {
    when(legalCostRepo.sumChargeableByContractNumberAsOf(eq(CN), any(LocalDate.class))).thenReturn(0L);

    service.recompute(CN);

    assertThat(schedules.get(0).getPaidCost()).isZero();
    assertThat(schedules.get(0).getLineStatus()).isEqualTo(PaymentSchedule.LINE_PAID);
    assertThat(schedules.get(1).getLineStatus()).isEqualTo(PaymentSchedule.LINE_PAID);
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
