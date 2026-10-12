package com.jdend.erp.loan.repayment;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.InterestCalcType;
import com.jdend.erp.contract.entity.RepaymentMethod;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.contract.support.AmountRounding;
import com.jdend.erp.legal.repository.LegalCostItemRepository;
import com.jdend.erp.loan.policy.AcceleratedRepaymentPolicy;
import com.jdend.erp.loan.policy.PaymentSurplusPolicy;
import com.jdend.erp.loan.writeoff.repository.WriteOffRepository;
import com.jdend.erp.payment.payment.entity.Payment;
import com.jdend.erp.payment.payment.repository.PaymentRepository;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

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
   * @return 마지막 수납 건의 충당 결과. 수납이 없으면 빈 결과.
   */
  @Transactional
  public RepaymentAllocation recompute(String contractNumber) {
    return recompute(contractNumber, null);
  }

  /**
   * 충당을 다시 계산하고 <b>지정한 수납 한 건</b>의 충당 결과를 돌려준다.
   *
   * 전표는 그 수납이 무엇에 충당됐는지를 적어야 한다. 그냥 '마지막 수납'의
   * 결과를 쓰면, 날짜를 소급해 등록한 수납의 전표에 다른 수납의 금액이 찍힌다.
   * 실제로 12월 수납이 있는 채권에 10월자 수납 100만원을 넣으면, 10월 전표의
   * 차변은 100만원인데 대변은 1,500만원으로 대차가 깨졌다.
   *
   * @param focusPaymentId 결과를 돌려받을 수납 id. null 이면 마지막 수납 기준.
   */
  @Transactional
  public RepaymentAllocation recompute(String contractNumber, Long focusPaymentId) {
    RepaymentAllocation last = new RepaymentAllocation();
    if (contractNumber == null || contractNumber.isBlank()) return last;

    Contract c = contractRepo.findByContractNumber(contractNumber).orElse(null);
    if (c == null) return last;

    List<PaymentSchedule> schedules = scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(contractNumber);

    // 상각일 이후 수납만 상각 순서(원금 우선)를 쓴다. 상각 전에 받은 수납은
    // 그때 일반 순서로 전표가 발행됐으므로 재계산해도 같은 결과가 나와야 한다.
    LocalDate writeOffDate = writeOffRepo.findFirstByContractNumberOrderByIdDesc(contractNumber)
        .map(w -> w.getWriteOffDate())
        .orElse(null);

    List<Payment> payments = orderedByApplyDate(contractNumber);

    // 충당과 회차 이자는 서로를 물고 있다.
    //   조기상환으로 원금이 줄면 이후 회차 이자가 줄고,
    //   이자가 줄면 같은 수납에서 원금으로 가는 몫이 늘어 원금이 또 줄어든다.
    // 그래서 '충당 → 이자 재계산'을 금액이 더 안 바뀔 때까지 되풀이한다.
    // 보통 두 번이면 멈춘다. 끝내 안 멈추면 마지막 결과를 쓰고 로그를 남긴다.
    NavigableMap<LocalDate, Long> timeline = new TreeMap<>();
    for (int pass = 1; pass <= MAX_INTEREST_PASSES; pass++) {
      timeline = new TreeMap<>();
      last = replay(c, schedules, payments, writeOffDate, contractNumber, timeline, focusPaymentId);

      if (!recalcScheduledInterest(c, schedules, timeline)) break;

      if (pass == MAX_INTEREST_PASSES) {
        log.warn("회차 이자 재계산이 {}회 안에 안정되지 않았습니다. contractNumber={}",
            MAX_INTEREST_PASSES, contractNumber);
      }
    }

    refreshRowBalances(schedules);
    scheduleRepo.saveAll(schedules);
    updateRemainingPrincipal(c, schedules);
    return last;
  }

  /** 충당을 처음부터 다시 흘려보낸다. timeline 에는 수납일별 '그 뒤의 남은 원금'이 쌓인다. */
  private RepaymentAllocation replay(Contract c, List<PaymentSchedule> schedules,
                                     List<Payment> payments, LocalDate writeOffDate,
                                     String contractNumber,
                                     NavigableMap<LocalDate, Long> timeline,
                                     Long focusPaymentId) {
    RepaymentAllocation last = new RepaymentAllocation();
    RepaymentAllocation focused = null;
    resetAllocations(schedules);

    // 변제충당 1순위 법적비용.
    // 수납 시점까지 '이미 발생한' 비용만 충당 대상이다. 총액을 그냥 쓰면 나중에 등록한
    // 비용이 그 이전 수납에까지 소급 적용돼, 이미 완납이던 회차가 부분납으로 되돌아가고
    // 없던 지연배상금이 붙는다. 상각 순서를 상각일 기준으로 정하는 것과 같은 이유다.
    long allocatedCost = 0L;

    for (Payment p : payments) {
      long amount = p.getPaymentAmount() == null ? 0L : p.getPaymentAmount();
      if (amount <= 0) continue;
      LocalDate date = effectiveDateOf(p);
      boolean writeOffOrder = writeOffDate != null && !date.isBefore(writeOffDate);

      long availableCost = Math.max(0L, chargeableCostAsOf(contractNumber, date) - allocatedCost);
      last = allocator.allocate(c, schedules, amount, date, availableCost, writeOffOrder,
          AcceleratedRepaymentPolicy.REDUCE_PRINCIPAL,
          PaymentSurplusPolicy.of(p.getSurplusPolicy()));
      allocatedCost += last.getCost();
      if (focusPaymentId != null && focusPaymentId.equals(p.getId())) focused = last;

      // 그 날짜까지 '실제로' 갚은 원금 누계. 약정상 줄어드는 몫과 구분해야
      // 조기상환으로 더 갚은 금액만 골라낼 수 있다.
      timeline.put(date, totalPaidPrincipal(schedules));
    }
    return focused != null ? focused : last;
  }

  /**
   * 수납일별 '그날 수납을 반영한 뒤의 남은 원금' 이력.
   *
   * 중도상환 정산에서 경과이자를 구간별로 나눠 계산하려면 "그 기간에 실제로 빌려준 돈이
   * 얼마였는지"를 알아야 한다. 수납 건별 원금 충당액은 어디에도 저장돼 있지 않고
   * 스케줄에 누적 합계로만 남으므로, 충당을 다시 재생해 이력을 만든다.
   *
   * 저장된 엔티티를 건드리지 않도록 복사본 위에서 재생한다.
   * (읽기 전용 트랜잭션의 flush 동작에 기대지 않는다)
   *
   * @return 수납일 → 그 날짜까지 반영한 뒤의 남은 원금. 수납이 없으면 빈 맵.
   */
  @Transactional(readOnly = true)
  public NavigableMap<LocalDate, Long> principalBalanceTimeline(String contractNumber) {
    NavigableMap<LocalDate, Long> timeline = new TreeMap<>();
    if (contractNumber == null || contractNumber.isBlank()) return timeline;

    Contract c = contractRepo.findByContractNumber(contractNumber).orElse(null);
    if (c == null) return timeline;

    List<PaymentSchedule> copies = scheduleRepo
        .findByContractNumberOrderByInstallmentNoAsc(contractNumber).stream()
        .map(RepaymentPostingService::detachedCopy)
        .toList();
    if (copies.isEmpty()) return timeline;

    LocalDate writeOffDate = writeOffRepo.findFirstByContractNumberOrderByIdDesc(contractNumber)
        .map(w -> w.getWriteOffDate())
        .orElse(null);

    long allocatedCost = 0L;
    for (Payment p : orderedByApplyDate(contractNumber)) {
      long amount = p.getPaymentAmount() == null ? 0L : p.getPaymentAmount();
      if (amount <= 0) continue;
      LocalDate date = effectiveDateOf(p);
      boolean writeOffOrder = writeOffDate != null && !date.isBefore(writeOffDate);

      long availableCost = Math.max(0L, chargeableCostAsOf(contractNumber, date) - allocatedCost);
      RepaymentAllocation a = allocator.allocate(c, copies, amount, date, availableCost, writeOffOrder,
          AcceleratedRepaymentPolicy.REDUCE_PRINCIPAL,
          PaymentSurplusPolicy.of(p.getSurplusPolicy()));
      allocatedCost += a.getCost();

      // 같은 날 여러 건이면 마지막 값이 그날의 최종 잔액이 된다.
      timeline.put(date, copies.stream().mapToLong(PaymentSchedule::unpaidPrincipal).sum());
    }
    return timeline;
  }

  /**
   * 이 수납을 며칠자로 쳐서 충당할지.
   *
   * 납입일 전에 미리 보내오는 일이 흔하다. 25일이 납입일인데 20일에 받으면
   * 받은 날로 계산한 경과이자는 닷새치가 모자라 약정한 월 납입액과 맞지 않는다.
   * 충당기준일을 적어 두지 않은 수납(기존 데이터 전부)은 받은 날을 그대로 쓴다.
   */
  static LocalDate effectiveDateOf(Payment p) {
    if (p == null) return LocalDate.now();
    if (p.getApplyDate() != null) return p.getApplyDate();
    return p.getPaymentDate() != null ? p.getPaymentDate() : LocalDate.now();
  }

  /**
   * 충당기준일 순서로 정렬한 수납 목록.
   *
   * 받은 날이 아니라 '며칠치로 치는가'가 충당 순서다. 20일에 받아 25일자로 처리한
   * 돈은 22일에 받아 22일자로 처리한 돈보다 뒤에 와야 그 사이 이자가 제 날짜로 계산된다.
   */
  private List<Payment> orderedByApplyDate(String contractNumber) {
    return paymentRepo.findByContractNumberOrderByPaymentDateAscIdAsc(contractNumber)
        .stream()
        .sorted(Comparator.comparing(RepaymentPostingService::effectiveDateOf)
            .thenComparing(Payment::getId, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  /** 충당 재생용 사본 — 영속 상태와 끊어 저장되지 않게 한다. */
  private static PaymentSchedule detachedCopy(PaymentSchedule s) {
    return PaymentSchedule.builder()
        .contractNumber(s.getContractNumber())
        .installmentNo(s.getInstallmentNo())
        .billStartDate(s.getBillStartDate())
        .billEndDate(s.getBillEndDate())
        .taxInvoiceDate(s.getTaxInvoiceDate())
        .paymentDate(s.getPaymentDate())
        .rentAmount(s.getRentAmount())
        .principalAmount(s.getPrincipalAmount())
        .interestAmount(s.getInterestAmount())
        .remainingPrincipal(s.getRemainingPrincipal())
        .paidPrincipal(0L)
        .paidInterest(0L)
        .paidOverdueInterest(0L)
        .paidCost(0L)
        .lineStatus(s.isAcceleratedLine() ? s.getLineStatus() : PaymentSchedule.LINE_UNPAID)
        .build();
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
  /** 충당·이자 재계산을 몇 번까지 되풀이할지 */
  private static final int MAX_INTEREST_PASSES = 5;

  /** 아직 갚지 않은 원금 합계 */
  private static long outstandingPrincipal(List<PaymentSchedule> schedules) {
    return schedules.stream().mapToLong(PaymentSchedule::unpaidPrincipal).sum();
  }

  /** 지금까지 실제로 갚은 원금 합계 */
  private static long totalPaidPrincipal(List<PaymentSchedule> schedules) {
    return schedules.stream().mapToLong(ps -> nz(ps.getPaidPrincipal())).sum();
  }

  /**
   * 회차 이자를 '그 기간에 실제로 남아 있던 원금' 기준으로 다시 계산한다.
   *
   * 조기상환으로 원금이 줄어도 회차 이자는 등록 당시 금액 그대로였다.
   * 500만원을 빌리고 일주일 뒤 200만원을 갚아도 이후 회차 이자가 계속
   * 500만원 기준이라, 실제로 받을 이자보다 많이 청구되고 있었다.
   *
   * 원금 상환 일정은 건드리지 않는다. 이자만 줄어 납입액이 그만큼 작아진다.
   *
   * @return 금액이 하나라도 바뀌었으면 true (바뀌었으면 충당을 다시 흘려보내야 한다)
   */
  private boolean recalcScheduledInterest(Contract c, List<PaymentSchedule> schedules,
                                          NavigableMap<LocalDate, Long> timeline) {
    if (schedules.isEmpty()) return false;
    BigDecimal rate = c.getInterestRate();
    if (rate == null || rate.signum() <= 0) return false;

    long loanAmount = c.getLoanAmount() == null ? 0L : c.getLoanAmount();
    AmountRounding rounding = AmountRounding.of(c.getRoundingUnit(), c.getRoundingMode());
    boolean daily = InterestCalcType.isDaily(c.getInterestCalcType())
        && !RepaymentMethod.EQUAL_PAYMENT.equals(c.getRepaymentMethod());

    List<PaymentSchedule> ordered = schedules.stream()
        .sorted(Comparator.comparing(PaymentSchedule::getInstallmentNo,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();

    boolean changed = false;
    long scheduledPaidBefore = 0L;      // 앞 회차들의 약정 원금 누계
    for (PaymentSchedule ps : ordered) {
      long scheduledOpening = Math.max(0L, loanAmount - scheduledPaidBefore);
      long thisPrincipal = nz(ps.getPrincipalAmount());

      // 기한이익상실로 청구가 멈춘 회차는 건드리지 않는다. 그 회차의 약정이자는
      // 이미 정산에서 접어 둔 것이라 여기서 되살리면 중복으로 붙는다.
      if (ps.isAcceleratedLine()) { scheduledPaidBefore += thisPrincipal; continue; }

      Long computed = periodInterest(ps, rate, daily, rounding, timeline,
          scheduledOpening, scheduledPaidBefore);
      scheduledPaidBefore += thisPrincipal;
      if (computed == null) continue;

      long before = ps.getInterestAmount() == null ? 0L : ps.getInterestAmount();
      if (before == computed) continue;

      ps.setInterestAmount(computed);
      ps.setRentAmount(nz(ps.getPrincipalAmount()) + computed);
      changed = true;
    }
    return changed;
  }

  /**
   * 한 회차 기간의 이자 — 그 기간에 실제로 남아 있던 원금에 붙인다.
   *
   * 기준 잔액은 두 가지를 합쳐 정한다.
   *   1. 약정상 잔액 — 대출금에서 앞 회차들의 약정 원금을 뺀 값.
   *      원금균등처럼 회차마다 원금이 줄어드는 방식에서 이자가 체감하는 근거다.
   *      연체로 실제로 못 받았더라도 약정이자는 제때 갚은 것으로 보고 매긴다
   *      (못 받은 몫은 지연배상금으로 따로 계산한다).
   *   2. 조기상환 — 약정보다 더 갚은 금액만큼 잔액을 더 줄인다.
   *      기간 중간에 갚았으면 그 날짜로 기간을 쪼개 앞뒤 잔액을 달리 본다.
   *
   * 일수는 스케줄 생성과 같게 시작일·종료일을 모두 센다. 반올림 방식도 생성기와
   * 맞춰 일할은 원 단위 절사 뒤 단수 적용, 월할은 단수 적용만 한다.
   * 그래야 조기상환이 없는 채권에서 등록 당시와 똑같은 금액이 나온다.
   *
   * @param scheduledOpening 이 회차 시작 시점의 약정 잔액
   * @param scheduledPaidBefore 이 회차 앞까지 약정상 갚았어야 할 원금 누계
   */
  private Long periodInterest(PaymentSchedule ps, BigDecimal annualRatePercent, boolean daily,
                              AmountRounding rounding, NavigableMap<LocalDate, Long> timeline,
                              long scheduledOpening, long scheduledPaidBefore) {
    LocalDate billStart = ps.getBillStartDate();
    LocalDate billEnd = ps.getBillEndDate();
    if (billStart == null || billEnd == null || billEnd.isBefore(billStart)) return null;

    // 경계를 '시작일 하루 전'으로 잡아 시작일도 하루로 세게 한다
    LocalDate from = billStart.minusDays(1);
    long totalDays = ChronoUnit.DAYS.between(from, billEnd);
    if (totalDays <= 0) return null;

    BigDecimal yearFraction = annualRatePercent.divide(BigDecimal.valueOf(100), 16, RoundingMode.HALF_UP);
    BigDecimal perDay = daily
        ? yearFraction.divide(BigDecimal.valueOf(365), 16, RoundingMode.HALF_UP)
        // 월할: 한 달치 이자를 그 기간의 일수로 나눠 하루치를 만든다.
        // 구간이 하나뿐이면 잔액 x 월이율 그대로라 기존 계산과 어긋나지 않는다.
        : yearFraction.divide(BigDecimal.valueOf(12), 16, RoundingMode.HALF_UP)
                      .divide(BigDecimal.valueOf(totalDays), 16, RoundingMode.HALF_UP);

    BigDecimal total = BigDecimal.ZERO;
    LocalDate cursor = from;
    while (cursor.isBefore(billEnd)) {
      LocalDate next = timeline.higherKey(cursor);
      LocalDate segEnd = (next == null || next.isAfter(billEnd)) ? billEnd : next;

      long days = ChronoUnit.DAYS.between(cursor, segEnd);
      if (days > 0) {
        Map.Entry<LocalDate, Long> e = timeline.floorEntry(cursor);
        long actuallyPaid = (e == null) ? 0L : e.getValue();
        // 약정보다 더 갚은 만큼만 잔액에서 더 뺀다
        long extraPrepaid = Math.max(0L, actuallyPaid - scheduledPaidBefore);
        long balance = Math.max(0L, scheduledOpening - extraPrepaid);

        if (balance > 0) {
          total = total.add(BigDecimal.valueOf(balance).multiply(perDay).multiply(BigDecimal.valueOf(days)));
        }
      }
      cursor = segEnd;
    }

    // 일할은 생성기(DailyInterestCalculator)와 같이 원 단위를 먼저 절사한다
    return daily
        ? rounding.apply(total.setScale(0, RoundingMode.DOWN).longValue())
        : rounding.apply(total);
  }

  /**
   * 회차별 '그 회차를 낸 뒤의 남은 원금'을 실제 충당 결과로 다시 채운다.
   * 조기상환 뒤에도 스케줄의 잔액 칸이 옛 숫자로 남아 있으면 화면이 어긋나 보인다.
   */
  private void refreshRowBalances(List<PaymentSchedule> schedules) {
    List<PaymentSchedule> ordered = schedules.stream()
        .sorted(Comparator.comparing(PaymentSchedule::getInstallmentNo,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();

    long remaining = outstandingPrincipal(ordered);
    for (PaymentSchedule ps : ordered) {
      remaining -= ps.unpaidPrincipal();
      ps.setRemainingPrincipal(Math.max(0L, remaining));
    }
  }

  private static long nz(Long v) { return v == null ? 0L : v; }

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
