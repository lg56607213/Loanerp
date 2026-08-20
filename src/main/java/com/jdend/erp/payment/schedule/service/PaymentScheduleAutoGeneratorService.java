package com.jdend.erp.payment.schedule.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.RepaymentMethod;
import com.jdend.erp.contract.support.AmortizationCalculator;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 여신계약의 상환스케줄 자동 생성.
 *
 * 정기 회차는 월할(연이율 ÷ 12)로 산출한다.
 * 중도상환·기한이익상실 등 정산 시점의 일할 재계산은 DailyInterestCalculator가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentScheduleAutoGeneratorService {

  private final PaymentScheduleRepository scheduleRepo;

  /**
   * 계약 기준 스케줄 자동 생성 (멱등).
   * 해당 채권번호에 스케줄이 0건일 때만 생성한다.
   */
  @Transactional
  public int ensureGenerated(Contract c) {
    if (c == null || c.getContractNumber() == null || c.getContractNumber().isBlank()) return 0;
    if (scheduleRepo.existsByContractNumber(c.getContractNumber())) return 0;
    return persist(c, build(c));
  }

  /**
   * 계약 조건 변경 시 미래 미수납 스케줄만 재생성한다.
   * 오늘 이후 시작하는 회차만 삭제하므로 과거 수납 이력은 보존된다.
   */
  @Transactional
  public int regenerate(Contract c) {
    if (c == null || c.getContractNumber() == null || c.getContractNumber().isBlank()) return 0;

    LocalDate today = LocalDate.now();
    scheduleRepo.deleteByContractNumberAndBillStartDateGreaterThanEqual(c.getContractNumber(), today);

    // 전체 회차를 동일하게 계산하되 오늘 이후 회차만 저장한다.
    // 이렇게 해야 잔여원금 흐름이 1회차부터 이어져 금액이 어긋나지 않는다.
    List<PaymentSchedule> all = build(c);
    List<PaymentSchedule> future = all.stream()
        .filter(ps -> ps.getBillStartDate() != null && !ps.getBillStartDate().isBefore(today))
        .toList();

    return persist(c, future);
  }

  private int persist(Contract c, List<PaymentSchedule> batch) {
    if (batch.isEmpty()) return 0;
    scheduleRepo.saveAll(batch);
    return batch.size();
  }

  /**
   * 회차별 원금·이자·잔여원금을 계산해 전체 스케줄을 만든다.
   *
   * <ul>
   *   <li>원리금균등 — 회차 납입액 고정, 이자는 잔액 기준, 원금은 나머지</li>
   *   <li>원금균등 — 회차 원금 고정, 이자는 잔액 기준으로 체감</li>
   *   <li>만기일시 — 매회 이자만, 마지막 회차에 원금 전액</li>
   * </ul>
   * 마지막 회차에서 생기는 단수 차이는 원금에 흡수시켜 잔여원금이 정확히 0으로 끝나게 한다.
   */
  private List<PaymentSchedule> build(Contract c) {
    List<PaymentSchedule> batch = new ArrayList<>();

    int installments = c.getInstallmentCount() == null ? 0 : c.getInstallmentCount();
    long principal = c.getLoanAmount() == null ? 0L : c.getLoanAmount();
    LocalDate start = c.getStartDate();
    if (installments <= 0 || principal <= 0 || start == null) return batch;

    String method = c.getRepaymentMethod() == null ? RepaymentMethod.EQUAL_PAYMENT : c.getRepaymentMethod();
    BigDecimal monthlyRate = AmortizationCalculator.monthlyRate(c.getInterestRate());

    long fixedPayment = c.getMonthlyPayment() == null || c.getMonthlyPayment() <= 0
        ? AmortizationCalculator.monthlyPayment(method, principal, c.getInterestRate(), installments)
        : c.getMonthlyPayment();

    long equalPrincipalPart = Math.round((double) principal / installments);
    int payDay = (c.getPaymentDay() != null && c.getPaymentDay() > 0)
        ? c.getPaymentDay()
        : start.getDayOfMonth();

    long remaining = principal;
    LocalDate billStart = start;

    for (int i = 1; i <= installments; i++) {
      LocalDate billEnd = billStart.plusMonths(1).minusDays(1);
      LocalDate dueDate = withDaySafe(start.plusMonths(i), payDay);

      long interest = AmortizationCalculator.monthlyInterest(remaining, monthlyRate);
      long principalPart = switch (method) {
        case RepaymentMethod.EQUAL_PRINCIPAL -> equalPrincipalPart;
        case RepaymentMethod.BULLET -> (i == installments) ? remaining : 0L;
        default -> fixedPayment - interest;
      };
      if (principalPart < 0) principalPart = 0;

      // 마지막 회차이거나 계산 원금이 잔액을 넘으면 잔액 전부를 상환해 0으로 맞춘다.
      if (i == installments || principalPart > remaining) {
        principalPart = remaining;
      }

      remaining -= principalPart;
      if (remaining < 0) remaining = 0;

      batch.add(PaymentSchedule.builder()
          .contract(c)
          .contractNumber(c.getContractNumber())
          .installmentNo(i)
          .billStartDate(billStart)
          .billEndDate(billEnd)
          .taxInvoiceDate(dueDate)
          .paymentDate(dueDate)
          .rentAmount(principalPart + interest)
          .principalAmount(principalPart)
          .interestAmount(interest)
          .remainingPrincipal(remaining)
          .build());

      billStart = billEnd.plusDays(1);
    }
    return batch;
  }

  /** 말일 클램핑 — 31일 납입일자를 2월에 적용하면 28/29일로 내린다. */
  private static LocalDate withDaySafe(LocalDate baseMonth, int day) {
    if (baseMonth == null) return null;
    if (day <= 0) day = 1;
    YearMonth ym = YearMonth.of(baseMonth.getYear(), baseMonth.getMonthValue());
    return LocalDate.of(baseMonth.getYear(), baseMonth.getMonthValue(), Math.min(day, ym.lengthOfMonth()));
  }
}
