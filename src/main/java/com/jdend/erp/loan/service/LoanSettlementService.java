package com.jdend.erp.loan.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.contract.support.DailyInterestCalculator;
import com.jdend.erp.contract.support.DebtTypeCode;
import com.jdend.erp.loan.policy.DebtorType;
import com.jdend.erp.loan.policy.PersonalDebtorProtection;
import com.jdend.erp.loan.dto.LoanSettlementResponse;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

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
    long accruedInterest = DailyInterestCalculator.accrued(
        remainingPrincipal, c.getInterestRate(), accrualFrom, asOf);

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
   * 지연배상금 — 연체된 회차마다 그 회차의 미납액에 대해 D+1부터 정산일까지 일할로 계산한다.
   * 회차별로 연체일수가 다르므로 합산해야 정확하다.
   */
  private long overdueInterest(Contract c, List<PaymentSchedule> schedules, LocalDate asOf, boolean charged) {
    if (!charged) return 0L;
    long sum = 0L;
    for (PaymentSchedule ps : schedules) {
      if (!isDue(ps, asOf)) continue;
      long unpaid = ps.unpaidTotal();
      if (unpaid <= 0) continue;
      LocalDate due = ps.getPaymentDate() != null ? ps.getPaymentDate() : ps.getTaxInvoiceDate();
      sum += DailyInterestCalculator.overdueInterest(true, unpaid, c.getOverdueRate(), due, asOf);
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
      .append("까지 ").append(days).append("일간 일할(365일) 계산했습니다.");
    if (c.getInterestRate() != null) {
      sb.append(" 약정이율 연 ").append(c.getInterestRate()).append("%.");
    }
    if (!charged) {
      sb.append(" 이 채권은 연체이자 미부과 대상이라 지연배상금을 산정하지 않았습니다.");
    } else if (c.getOverdueRate() != null) {
      sb.append(" 지연배상금은 연체 회차별 미납액에 연 ")
        .append(c.getOverdueRate()).append("%를 D+1부터 일할 적용했습니다.");
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
