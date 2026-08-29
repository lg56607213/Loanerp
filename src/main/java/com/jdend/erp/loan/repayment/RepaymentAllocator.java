package com.jdend.erp.loan.repayment;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.support.DailyInterestCalculator;
import com.jdend.erp.loan.policy.AcceleratedRepaymentPolicy;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 변제충당.
 *
 * 채권 상태에 따라 순서가 달라진다.
 *  - 일반 채권(정상/연체/해지): 법적비용 → 이자 → 원금
 *  - 상각 채권: 법적비용 → 원금 → 이자
 *    상각채권은 회수 자체가 불확실하므로 원금을 먼저 회수해 회수율을 확보한다.
 *
 * '이자' 단계 안에서는 지연배상금을 약정이자보다 먼저 충당한다.
 * 각 단계는 납입예정일이 오래된 회차부터 채운다.
 */
@Component
public class RepaymentAllocator {

  /** 충당 단계 */
  private enum Step { COST, OVERDUE_INTEREST, INTEREST, PRINCIPAL }

  private static final List<Step> ORDER_GENERAL =
      List.of(Step.COST, Step.OVERDUE_INTEREST, Step.INTEREST, Step.PRINCIPAL);

  private static final List<Step> ORDER_WRITTEN_OFF =
      List.of(Step.COST, Step.PRINCIPAL, Step.OVERDUE_INTEREST, Step.INTEREST);

  /**
   * @param contract     채권
   * @param schedules    상환스케줄 전체
   * @param amount       입금액
   * @param paymentDate  수납일 — 지연배상금 계산 기준일
   * @param outstandingCost 미회수 법적비용
   */
  public RepaymentAllocation allocate(Contract contract, List<PaymentSchedule> schedules,
                                      long amount, LocalDate paymentDate, long outstandingCost) {
    return allocate(contract, schedules, amount, paymentDate, outstandingCost,
        ContractStatus.WRITTEN_OFF.equals(contract.getStatus()));
  }

  /**
   * @param writeOffOrder 상각 순서(법적비용 -> 원금 -> 이자)를 쓸지 여부.
   *   수납 시점 기준으로 정해야 한다. 상각 등록 이전에 받은 수납까지 상각 순서로
   *   다시 계산하면 이미 발행된 전표(이자수익)와 스케줄 충당이 어긋난다.
   */
  public RepaymentAllocation allocate(Contract contract, List<PaymentSchedule> schedules,
                                      long amount, LocalDate paymentDate, long outstandingCost,
                                      boolean writeOffOrder) {
    return allocate(contract, schedules, amount, paymentDate, outstandingCost, writeOffOrder,
        AcceleratedRepaymentPolicy.REDUCE_PRINCIPAL);
  }

  /**
   * @param acceleratedPolicy 기한이익상실로 청구중지된 회차의 원금을 갚을 수 있게 할지.
   *   기본값 REDUCE_PRINCIPAL. HOLD_AS_PREPAID 면 도래 회차만 충당하고 나머지는 선수금으로 남는다.
   */
  public RepaymentAllocation allocate(Contract contract, List<PaymentSchedule> schedules,
                                      long amount, LocalDate paymentDate, long outstandingCost,
                                      boolean writeOffOrder,
                                      AcceleratedRepaymentPolicy acceleratedPolicy) {
    RepaymentAllocation result = new RepaymentAllocation();
    if (amount <= 0) return result;

    LocalDate asOf = paymentDate != null ? paymentDate : LocalDate.now();
    boolean overdueCharged = !Boolean.FALSE.equals(contract.getOverdueChargeYn());

    // 이자·지연배상금 충당 대상. 청구중지 회차는 여기 들어가지 않는다.
    // 조기 상환된 구간의 약정이자는 발생하지 않았고, 미도래 원금에는 가산이자도 붙지 않는다.
    List<PaymentSchedule> targets = schedules.stream()
        .filter(ps -> !ps.isAcceleratedLine())
        .sorted(Comparator.comparing(RepaymentAllocator::dueDateOf,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();

    // 원금 충당 대상. 기본 정책에서는 청구중지 회차의 원금도 갚을 수 있어야 한다.
    // '청구를 중지한다'와 '변제를 받지 않는다'는 다른 이야기다.
    List<PaymentSchedule> principalTargets =
        acceleratedPolicy == AcceleratedRepaymentPolicy.REDUCE_PRINCIPAL
            ? schedules.stream()
                .sorted(Comparator.comparing(RepaymentAllocator::dueDateOf,
                    Comparator.nullsLast(Comparator.naturalOrder())))
                .toList()
            : targets;

    long remain = amount;
    List<Step> order = writeOffOrder ? ORDER_WRITTEN_OFF : ORDER_GENERAL;

    for (Step step : order) {
      if (remain <= 0) break;
      remain = switch (step) {
        case COST -> applyCost(result, targets, schedules, remain, outstandingCost);
        case OVERDUE_INTEREST -> applyOverdueInterest(result, targets, remain, contract, asOf, overdueCharged);
        case INTEREST -> applyInterest(result, targets, remain, asOf);
        case PRINCIPAL -> applyPrincipal(result, principalTargets, remain, asOf);
      };
    }

    result.setExcess(Math.max(0L, remain));
    return result;
  }

  /**
   * 법적비용 충당 (1순위).
   *
   * 법적비용은 회차가 아니라 채권 단위로 발생하므로 회차별로 나눌 의미가 없다.
   * 다만 재계산(replay) 시 초기화 대상이어야 하고 부분 회수도 추적해야 하므로,
   * 실적은 가장 오래된 회차의 paidCost에 모아 기록한다.
   * paidCost는 회차 완납 판정(dueTotal/paidTotal)에는 들어가지 않는다.
   *
   * 기한이익상실로 전 회차가 청구중지된 채권은 targets가 비므로, 그때는 전체 스케줄의
   * 첫 회차에 기록한다. 기록할 곳이 아예 없으면(스케줄 0건) 충당 자체를 하지 않는다 —
   * 기록하지 않고 금액만 차감하면 재계산 때 같은 비용을 두 번 충당하게 된다.
   */
  private long applyCost(RepaymentAllocation result, List<PaymentSchedule> targets,
                         List<PaymentSchedule> allSchedules, long remain, long outstandingCost) {
    if (outstandingCost <= 0) return remain;

    PaymentSchedule head = !targets.isEmpty() ? targets.get(0)
                         : (!allSchedules.isEmpty() ? allSchedules.get(0) : null);
    if (head == null) return remain;

    long take = Math.min(remain, outstandingCost);
    result.addCost(take);
    head.setPaidCost(nz(head.getPaidCost()) + take);
    return remain - take;
  }

  private long applyOverdueInterest(RepaymentAllocation result, List<PaymentSchedule> targets,
                                    long remain, Contract contract, LocalDate asOf, boolean charged) {
    if (!charged) return remain;
    for (PaymentSchedule ps : targets) {
      if (remain <= 0) break;
      if (!isDue(ps, asOf)) continue;

      // 지연배상금은 '미납 원금'에만 붙인다. 미납 이자에까지 붙이면 이자에 이자를
      // 물리는 복리가 되어 이자제한법에 걸린다. 지연손해금은 원본에 대해 발생한다.
      long overduePrincipal = ps.unpaidPrincipal();
      if (overduePrincipal <= 0) continue;

      long accrued = DailyInterestCalculator.overdueInterest(
          true, overduePrincipal, contract.getOverdueRate(), dueDateOf(ps), asOf);
      long already = nz(ps.getPaidOverdueInterest());
      long due = accrued - already;
      if (due <= 0) continue;

      long take = Math.min(remain, due);
      ps.setPaidOverdueInterest(already + take);
      result.addOverdueInterest(take);
      result.lineFor(ps.getId(), ps.getInstallmentNo()).addOverdueInterest(take);
      remain -= take;
    }
    return remain;
  }

  private long applyInterest(RepaymentAllocation result, List<PaymentSchedule> targets,
                             long remain, LocalDate asOf) {
    for (PaymentSchedule ps : targets) {
      if (remain <= 0) break;
      if (!isDue(ps, asOf)) continue;

      long due = ps.unpaidInterest();
      if (due <= 0) continue;

      long take = Math.min(remain, due);
      ps.setPaidInterest(nz(ps.getPaidInterest()) + take);
      ps.refreshLineStatus();
      result.addInterest(take);
      result.lineFor(ps.getId(), ps.getInstallmentNo()).addInterest(take);
      remain -= take;
    }
    return remain;
  }

  /**
   * 원금 충당. 도래한 회차를 먼저 채우고, 그래도 남으면 미도래 회차의 원금을 앞당겨 충당한다
   * (중도상환·조기완제).
   */
  private long applyPrincipal(RepaymentAllocation result, List<PaymentSchedule> targets,
                              long remain, LocalDate asOf) {
    remain = fillPrincipal(result, targets, remain, ps -> isDue(ps, asOf));
    if (remain > 0) {
      remain = fillPrincipal(result, targets, remain, ps -> !isDue(ps, asOf));
    }
    return remain;
  }

  private long fillPrincipal(RepaymentAllocation result, List<PaymentSchedule> targets,
                             long remain, java.util.function.Predicate<PaymentSchedule> filter) {
    for (PaymentSchedule ps : targets) {
      if (remain <= 0) break;
      if (!filter.test(ps)) continue;

      long due = ps.unpaidPrincipal();
      if (due <= 0) continue;

      long take = Math.min(remain, due);
      ps.setPaidPrincipal(nz(ps.getPaidPrincipal()) + take);
      ps.refreshLineStatus();
      result.addPrincipal(take);
      result.lineFor(ps.getId(), ps.getInstallmentNo()).addPrincipal(take);
      remain -= take;
    }
    return remain;
  }

  private static boolean isDue(PaymentSchedule ps, LocalDate asOf) {
    LocalDate d = dueDateOf(ps);
    return d != null && !d.isAfter(asOf);
  }

  private static LocalDate dueDateOf(PaymentSchedule ps) {
    return ps.getPaymentDate() != null ? ps.getPaymentDate() : ps.getTaxInvoiceDate();
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
