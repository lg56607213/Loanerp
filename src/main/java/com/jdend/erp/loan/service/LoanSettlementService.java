package com.jdend.erp.loan.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.contract.support.DailyInterestCalculator;
import com.jdend.erp.contract.support.DebtTypeCode;
import com.jdend.erp.loan.policy.DebtorType;
import com.jdend.erp.loan.policy.PersonalDebtorProtection;
import com.jdend.erp.loan.dto.LoanSettlementResponse;
import com.jdend.erp.loan.repayment.RepaymentPostingService;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 정산 계산 — 중도상환·기한이익상실·완제 시점에 얼마를 청구해야 하는지 산출한다.
 *
 * 정기 회차 스케줄은 월할이지만, 정산은 실제 경과일수 기준이라 반드시 일할(365일)로 다시 계산한다.
 * 월할 그대로 쓰면 정산일이 회차 중간일 때 이자가 며칠치 어긋난다.
 *
 * <p><b>기한이익상실 이후 지연배상금.</b> 개인채무자보호법(개인금융채권의 관리 및
 * 개인금융채무자의 보호에 관한 법률)상, 최초원금 5,000만원 미만인 개인금융채권은
 * 기한이익상실이 나더라도 <b>원래 납기일이 도래하지 않은 원금에 연체가산이자를
 * 붙일 수 없다.</b> 그래서 지연배상금은 언제나 '원래 납기일이 도래한 미납 회차'에만
 * 붙인다. 잔여원금 전액에 일할 기산하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LoanSettlementService {

  private final ContractRepository contractRepo;
  private final PaymentScheduleRepository scheduleRepo;
  private final RepaymentPostingService repaymentPosting;

  @Transactional(readOnly = true)
  public LoanSettlementResponse settle(String contractNumber, LocalDate settlementDate) {
    if (contractNumber == null || contractNumber.isBlank()) {
      throw new IllegalArgumentException("채권번호는 필수입니다.");
    }
    LocalDate asOf = settlementDate != null ? settlementDate : LocalDate.now();

    Contract c = contractRepo.findWithCustomerByContractNumber(contractNumber.trim())
        .orElseThrow(() -> new IllegalArgumentException("채권 없음: " + contractNumber));

    List<PaymentSchedule> schedules = scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(c.getContractNumber());

    long remainingPrincipal = remainingPrincipal(c, schedules, asOf);
    long unpaidInterest = schedules.stream()
        .filter(ps -> isDue(ps, asOf))
        .mapToLong(PaymentSchedule::unpaidInterest)
        .sum();

    LocalDate accrualFrom = lastDueDateOnOrBefore(schedules, asOf, c.getStartDate());
    long accrualDays = DailyInterestCalculator.overdueDays(accrualFrom, asOf);
    long accruedInterest = accruedOnSchedule(c, schedules, asOf, remainingPrincipal);

    boolean charged = !Boolean.FALSE.equals(c.getOverdueChargeYn());
    boolean protectionApplies = protectionApplies(c);
    long overdueInterest = overdueInterest(c, schedules, asOf, charged);

    long total = remainingPrincipal + unpaidInterest + accruedInterest + overdueInterest;

    return LoanSettlementResponse.builder()
        .contractNumber(c.getContractNumber())
        .customerName(c.getCustomer() != null ? c.getCustomer().getCustomerName() : null)
        .settlementDate(asOf)
        .remainingPrincipal(remainingPrincipal)
        .unpaidInterest(unpaidInterest)
        .accruedInterest(accruedInterest)
        .accrualFrom(accrualFrom)
        .accrualDays((int) accrualDays)
        .overdueInterest(overdueInterest)
        .overdueCharged(charged)
        .legalCost(0L)
        .totalDue(total)
        .note(buildNote(c, accrualFrom, asOf, accrualDays, charged, protectionApplies))
        .build();
  }

  /**
   * 경과이자 — 기간 중 실제 잔액으로 구간을 나눠 계산한다.
   *
   * 예전에는 정산일 기준 잔액 하나로 기간 전체를 계산했다. 그래서 기간 중간에
   * 원금을 갚으면 그 앞 기간까지 줄어든 잔액으로 이자가 매겨져 적게 청구됐다.
   * (500만원을 빌려주고 7일 뒤 일부를 받은 건에서, 17,260원이어야 할 이자가
   *  상환 후 잔액 기준으로 10,329원만 나왔다)
   *
   * 수납일마다 잔액이 바뀌므로 그 지점에서 구간을 끊고 각 구간은 그때의 잔액으로 매긴다.
   */
  /**
   * 경과이자 — 아직 도래하지 않은 회차에서 지금까지 굴러간 이자.
   *
   * <p>변제충당(RepaymentAllocator)과 <b>같은 식</b>을 쓴다. 두 계산이 어긋나면
   * 화면이 알려준 완납금액을 그대로 넣어도 다 충당되지 않고 차액이 선수금으로 남는다.
   *
   * <p>전에는 '미회수 원금 전액'에 일할로 매겼다. 연체 채권에서는 그 원금에 이미
   * 지연배상금이 붙고 있어 같은 원금에 이자를 두 번 물리는 셈이었다. 실제로
   * 1,000만원 원금균등 9회차 연체 건에서 36,412원이 과다 산정돼 선수금으로 떨어졌다.
   * 이제 회차에 예정된 이자를 넘지 않게 해 중복을 끊는다.
   */
  /**
   * 회차 약정이자 중 기준일까지 지나간 몫.
   *
   * 일수는 스케줄 생성과 같게 시작일·종료일을 모두 센다.
   * 기간이 다 지났으면 전액, 아직 시작 전이면 0.
   */
  private static long proRatedScheduledInterest(PaymentSchedule ps, LocalDate asOf) {
    long scheduled = nz(ps.getInterestAmount());
    if (scheduled <= 0) return 0L;

    LocalDate start = ps.getBillStartDate();
    LocalDate end = ps.getBillEndDate();
    if (start == null || end == null || asOf == null) return scheduled;

    long total = ChronoUnit.DAYS.between(start, end) + 1;
    if (total <= 0) return scheduled;

    long elapsed = ChronoUnit.DAYS.between(start, asOf) + 1;
    if (elapsed <= 0) return 0L;
    if (elapsed >= total) return scheduled;

    return BigDecimal.valueOf(scheduled)
        .multiply(BigDecimal.valueOf(elapsed))
        .divide(BigDecimal.valueOf(total), 0, RoundingMode.DOWN)
        .longValue();
  }

  private long accruedOnSchedule(Contract c, List<PaymentSchedule> schedules,
                                 LocalDate asOf, long outstandingPrincipal) {
    if (asOf == null || outstandingPrincipal <= 0) return 0L;

    long sum = 0L;
    for (PaymentSchedule ps : schedules) {
      // 기한이익상실로 청구가 멈춘 회차는 세지 않는다
      if (ps.isAcceleratedLine()) continue;
      // 도래한 회차의 이자는 미납이자에서 이미 세고 있다
      if (isDue(ps, asOf)) continue;

      LocalDate from = ps.getBillStartDate();
      if (from == null || !asOf.isAfter(from)) continue;
      if (ps.getBillEndDate() != null && asOf.isAfter(ps.getBillEndDate())) continue;

      long accrued = DailyInterestCalculator.accrued(
          outstandingPrincipal, c.getInterestRate(), from, asOf);

      // 그 회차에 예정된 이자 중 '오늘까지 지나간 몫'을 넘지 못한다.
      //
      // 회차 전체 이자로 막으면 아직 오지 않은 날의 이자까지 받게 된다.
      // 완제하면 그 회차는 중간에 끝나므로, 스케줄도 지나간 날수만큼만 이자를
      // 매긴다(RepaymentPostingService 의 회차 이자 재계산). 같은 비율로 자른다.
      long scheduled = proRatedScheduledInterest(ps, asOf);
      if (scheduled > 0) accrued = Math.min(accrued, scheduled);

      sum += Math.max(0L, accrued - nz(ps.getPaidInterest()));
    }
    return sum;
  }

  /**
   * 개인채무자보호법 적용 여부.
   * 최초원금(대출금) 5,000만원 미만 + 개인 + 개인금융채권이면 적용된다.
   */
  private boolean protectionApplies(Contract c) {
    return PersonalDebtorProtection.applies(
        c.getLoanAmount() == null ? 0L : c.getLoanAmount(),
        "법인".equals(c.getCustomerType()) ? DebtorType.CORPORATE : DebtorType.INDIVIDUAL,
        DebtTypeCode.toDebtType(c.getDebtType()));
  }

  /**
   * 정산일 기준 잔여원금.
   * 스케줄이 있으면 미납 회차의 원금 합계를, 없으면 계약의 잔여원금을 쓴다.
   */
  private long remainingPrincipal(Contract c, List<PaymentSchedule> schedules, LocalDate asOf) {
    if (schedules.isEmpty()) {
      return nz(c.getRemainingPrincipal()) > 0 ? nz(c.getRemainingPrincipal()) : nz(c.getLoanAmount());
    }
    return schedules.stream().mapToLong(PaymentSchedule::unpaidPrincipal).sum();
  }

  /**
   * 정산일까지 '원래 납기일'이 도래한 회차인지.
   *
   * <p>청구중지(기한이익상실로 조기 변제기가 온) 회차는 제외한다. 조기 변제기가 왔다고
   * 연체가 된 것이 아니기 때문이다. 이 한 줄이 개인채무자보호법상
   * '미도래 원금에 연체가산이자 부과 금지'를 지켜 주는 지점이다.
   */
  private boolean isDue(PaymentSchedule ps, LocalDate asOf) {
    if (ps.isAcceleratedLine()) return false;
    LocalDate due = ps.getPaymentDate() != null ? ps.getPaymentDate() : ps.getTaxInvoiceDate();
    return due != null && !due.isAfter(asOf);
  }

  /**
   * 지연배상금 — 연체된 회차마다 그 회차의 <b>미납 원금</b>에 대해
   * D+1부터 정산일까지 일할로 계산한다. 회차별로 연체일수가 다르므로 합산해야 정확하다.
   *
   * <p>기준액은 미납 <b>원금</b>이다. 미납 이자에까지 지연배상금을 붙이면 이자에 이자를
   * 물리는 복리가 되어 이자제한법 제한을 받는다. 지연손해금은 원본에 대해 발생한다(민법 제397조).
   */
  private long overdueInterest(Contract c, List<PaymentSchedule> schedules, LocalDate asOf, boolean charged) {
    if (!charged) return 0L;
    long sum = 0L;
    for (PaymentSchedule ps : schedules) {
      if (!isDue(ps, asOf)) continue;
      long overduePrincipal = ps.unpaidPrincipal();
      if (overduePrincipal <= 0) continue;
      LocalDate due = ps.getPaymentDate() != null ? ps.getPaymentDate() : ps.getTaxInvoiceDate();
      sum += DailyInterestCalculator.overdueInterest(true, overduePrincipal, c.getOverdueRate(), due, asOf);
    }
    return sum;
  }

  /** 경과이자 기산일 — 정산일 이전의 가장 최근 납입예정일. 없으면 계약 시작일. */
  private LocalDate lastDueDateOnOrBefore(List<PaymentSchedule> schedules, LocalDate asOf, LocalDate fallback) {
    return schedules.stream()
        .map(ps -> ps.getPaymentDate() != null ? ps.getPaymentDate() : ps.getTaxInvoiceDate())
        .filter(d -> d != null && !d.isAfter(asOf))
        .max(Comparator.naturalOrder())
        .orElse(fallback);
  }

  private String buildNote(Contract c, LocalDate from, LocalDate asOf, long days,
                          boolean charged, boolean protectionApplies) {
    StringBuilder sb = new StringBuilder();
    sb.append("경과이자는 ").append(from).append(" 다음날부터 ").append(asOf)
      .append("까지 ").append(days).append("일간 일할(365일) 계산했습니다.")
      .append(" 다만 그 회차에 예정된 이자를 넘지 않습니다 —")
      .append(" 연체 원금에는 지연배상금이 따로 붙으므로 같은 원금에 이자를 두 번 물리지 않기 위함입니다.");
    if (c.getInterestRate() != null) {
      sb.append(" 약정이율 연 ").append(c.getInterestRate()).append("%.");
    }
    if (!charged) {
      sb.append(" 이 채권은 연체이자 미부과 대상이라 지연배상금을 산정하지 않았습니다.");
    } else if (c.getOverdueRate() != null) {
      sb.append(" 지연배상금은 연체 회차별 미납 원금에 연 ")
        .append(c.getOverdueRate())
        .append("%를 D+1부터 일할 적용했습니다. 미납 이자에는 지연배상금을 붙이지 않습니다.");
    }
    if (charged && ContractStatus.ACCELERATED.equals(c.getStatus())) {
      sb.append(protectionApplies
          ? " 이 채권은 개인채무자보호법 적용 대상(최초원금 5,000만원 미만 개인금융채권)이라,"
            + " 기한이익상실로 조기 변제기가 도래한 원금에는 연체가산이자를 붙이지 않았습니다."
            + " 원래 납기일이 지난 미납 회차에만 연체이율을 적용했습니다."
          : " 기한이익상실 채권입니다. 조기 변제기가 도래한 원금에는 연체가산이자를 붙이지 않고,"
            + " 원래 납기일이 지난 미납 회차에만 연체이율을 적용했습니다.");
    }
    return sb.toString();
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
