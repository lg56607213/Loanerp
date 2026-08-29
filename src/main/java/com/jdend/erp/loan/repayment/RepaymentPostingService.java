package com.jdend.erp.loan.repayment;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.legal.repository.LegalCostItemRepository;
import com.jdend.erp.loan.writeoff.repository.WriteOffRepository;
import com.jdend.erp.payment.payment.entity.Payment;
import com.jdend.erp.payment.payment.repository.PaymentRepository;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 수납 → 변제충당 반영.
 *
 * 수납은 등록뿐 아니라 수정·삭제도 되기 때문에, 충당 실적을 증분으로 더하고 빼면
 * 금방 어긋난다. 그래서 해당 채권의 수납 전체를 날짜순으로 다시 흘려보내 충당을
 * 처음부터 계산한다(replay). 회차 수가 많아야 수백 건이라 비용도 크지 않다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepaymentPostingService {

  private final PaymentScheduleRepository scheduleRepo;
  private final PaymentRepository paymentRepo;
  private final ContractRepository contractRepo;
  private final RepaymentAllocator allocator;
  private final WriteOffRepository writeOffRepo;
  private final LegalCostItemRepository legalCostRepo;

  /**
   * 채권의 충당 실적을 전부 다시 계산하고 잔여원금을 갱신한다.
   * @return 마지막 수납 건의 충당 결과 (전표 분개에 사용). 수납이 없으면 빈 결과.
   */
  @Transactional
  public RepaymentAllocation recompute(String contractNumber) {
    RepaymentAllocation last = new RepaymentAllocation();
    if (contractNumber == null || contractNumber.isBlank()) return last;

    Contract c = contractRepo.findByContractNumber(contractNumber).orElse(null);
    if (c == null) return last;

    List<PaymentSchedule> schedules = scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(contractNumber);
    resetAllocations(schedules);

    // 상각일 이후 수납만 상각 순서(원금 우선)를 쓴다. 상각 전에 받은 수납은
    // 그때 일반 순서로 전표가 발행됐으므로 재계산해도 같은 결과가 나와야 한다.
    LocalDate writeOffDate = writeOffRepo.findFirstByContractNumberOrderByIdDesc(contractNumber)
        .map(w -> w.getWriteOffDate())
        .orElse(null);

    // 변제충당 1순위 법적비용.
    // 수납 시점까지 '이미 발생한' 비용만 충당 대상이다. 총액을 그냥 쓰면 나중에 등록한
    // 비용이 그 이전 수납에까지 소급 적용돼, 이미 완납이던 회차가 부분납으로 되돌아가고
    // 없던 지연배상금이 붙는다. 상각 순서를 상각일 기준으로 정하는 것과 같은 이유다.
    long allocatedCost = 0L;

    List<Payment> payments = paymentRepo.findByContractNumberOrderByPaymentDateAscIdAsc(contractNumber);
    for (Payment p : payments) {
      long amount = p.getPaymentAmount() == null ? 0L : p.getPaymentAmount();
      if (amount <= 0) continue;
      LocalDate date = p.getPaymentDate() != null ? p.getPaymentDate() : LocalDate.now();
      boolean writeOffOrder = writeOffDate != null && !date.isBefore(writeOffDate);

      long availableCost = Math.max(0L, chargeableCostAsOf(contractNumber, date) - allocatedCost);
      last = allocator.allocate(c, schedules, amount, date, availableCost, writeOffOrder);
      allocatedCost += last.getCost();
    }

    scheduleRepo.saveAll(schedules);
    updateRemainingPrincipal(c, schedules);
    return last;
  }

  /**
   * 단일 수납 건의 충당 결과만 계산한다(저장 없음).
   * 전표 분개 라인을 만들 때 쓴다.
   */
  @Transactional(readOnly = true)
  public RepaymentAllocation simulate(Contract contract, long amount, LocalDate paymentDate) {
    List<PaymentSchedule> schedules =
        scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(contract.getContractNumber());
    // 이미 반영된 충당 실적 위에 이번 입금만 얹어 본다.
    LocalDate asOf = paymentDate != null ? paymentDate : LocalDate.now();
    long outstandingCost = chargeableCostAsOf(contract.getContractNumber(), asOf)
        - schedules.stream().mapToLong(ps -> ps.getPaidCost() == null ? 0L : ps.getPaidCost()).sum();
    return allocator.allocate(contract, schedules, amount, paymentDate, Math.max(0L, outstandingCost));
  }

  /**
   * 기준일까지 발생한 법적비용 합계 (법적절차 모듈 기준).
   * 신청비용·추가비용·확인비용은 더하고 환입은 뺀다. 법적절차가 없으면 0이다.
   */
  private long chargeableCostAsOf(String contractNumber, LocalDate asOf) {
    Long v = legalCostRepo.sumChargeableByContractNumberAsOf(contractNumber, asOf);
    return v == null ? 0L : v;
  }

  private void resetAllocations(List<PaymentSchedule> schedules) {
    for (PaymentSchedule ps : schedules) {
      ps.setPaidPrincipal(0L);
      ps.setPaidInterest(0L);
      ps.setPaidOverdueInterest(0L);
      ps.setPaidCost(0L);
      // 기한이익상실 회차(청구중지·일시청구)는 수납과 무관한 상태라 유지한다.
      if (!ps.isAcceleratedLine()) {
        ps.setLineStatus(PaymentSchedule.LINE_UNPAID);
      }
    }
  }

  /** 잔여원금 = 회차 원금 합계 − 충당된 원금 합계 */
  private void updateRemainingPrincipal(Contract c, List<PaymentSchedule> schedules) {
    if (schedules.isEmpty()) return;
    long remaining = schedules.stream()
        .sorted(Comparator.comparing(PaymentSchedule::getInstallmentNo))
        .mapToLong(PaymentSchedule::unpaidPrincipal)
        .sum();
    c.setRemainingPrincipal(remaining);
    contractRepo.save(c);
  }
}
