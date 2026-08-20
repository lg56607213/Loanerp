package com.jdend.erp.loan.repayment;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.support.DailyInterestCalculator;
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
    RepaymentAllocation result = new RepaymentAllocation();
    if (amount <= 0) return result;

    LocalDate asOf = paymentDate != null ? paymentDate : LocalDate.now();
    boolean overdueCharged = !Boolean.FALSE.equals(contract.getOverdueChargeYn());

    List<PaymentSchedule> targets = schedules.stream()
        .filter(ps -> !PaymentSchedule.LINE_SUSPENDED.equals(ps.getLineStatus()))
        .sorted(Comparator.comparing(RepaymentAllocator::dueDateOf,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();

    long remain = amount;
    List<Step> order = ContractStatus.WRITTEN_OFF.equals(contract.getStatus())
        ? ORDER_WRITTEN_OFF : ORDER_GENERAL;

    for (Step step : order) {
      if (remain <= 0) break;
      remain = switch (step) {
        case COST -> applyCost(result, remain, outstandingCost);
        case OVERDUE_INTEREST -> applyOverdueInterest(result, targets, remain, contract, asOf, overdueCharged);
        case INTEREST -> applyInterest(result, targets, remain, asOf);
        case PRINCIPAL -> applyPrincipal(result, targets, remain, asOf);
      };
    }

    result.setExcess(Math.max(0L, remain));
    return result;
  }

  private long applyCost(RepaymentAllocation result, long remain, long outstandingCost) {
    if (outstandingCost <= 0) return remain;
    long take = Math.min(remain, outstandingCost);
    result.addCost(take);
    return remain - take;
  }

  private long applyOverdueInterest(RepaymentAllocation result, List<PaymentSchedule> targets,
                                    long remain, Contract contract, LocalDate asOf, boolean charged) {
    if (!charged) return remain;
    for (PaymentSchedule ps : targets) {
      if (remain <= 0) break;
      if (!isDue(ps, asOf)) continue;

      long unpaid = ps.unpaidTotal();
      if (unpaid <= 0) continue;

      long accrued = DailyInterestCalculator.overdueInterest(
          true, unpaid, contract.getOverdueRate(), dueDateOf(ps), asOf);
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
