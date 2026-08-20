package com.jdend.erp.dashboard.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.dashboard.dto.LoanPortfolioResponse;
import com.jdend.erp.dashboard.dto.OverdueAgingResponse;
import com.jdend.erp.payment.overdue.dto.OverdueRowResponse;
import com.jdend.erp.payment.overdue.service.OverdueService;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 대부업 대시보드 지표.
 *
 * 채권 상태는 저장값(해지/상각/종료)과 파생값(정상/연체)을 나눠 판정한다.
 * 조회 화면과 같은 규칙을 써야 대시보드 숫자와 채권현황 숫자가 어긋나지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LoanDashboardService {

  /** 연체 경과기간 구간 (상한일, 라벨). 마지막은 상한 없음. */
  private static final int[] AGING_BOUNDS = {30, 60, 90};
  private static final String[] AGING_LABELS = {"1~30일", "31~60일", "61~90일", "90일 초과"};

  private final ContractRepository contractRepo;
  private final PaymentScheduleRepository scheduleRepo;
  private final OverdueService overdueService;

  /** 여신 포트폴리오 요약 */
  @Transactional(readOnly = true)
  public LoanPortfolioResponse portfolio() {
    List<Contract> all = contractRepo.findAll();
    Set<String> overdueNumbers = overdueService.overdueContractNumbers();

    Map<String, Integer> countByStatus = new LinkedHashMap<>();
    Map<String, Long> principalByStatus = new LinkedHashMap<>();
    for (String s : List.of(ContractStatus.NORMAL, ContractStatus.OVERDUE,
                            ContractStatus.ACCELERATED, ContractStatus.WRITTEN_OFF,
                            ContractStatus.CLOSED)) {
      countByStatus.put(s, 0);
      principalByStatus.put(s, 0L);
    }

    for (Contract c : all) {
      String st = derive(c, overdueNumbers);
      countByStatus.merge(st, 1, Integer::sum);
      principalByStatus.merge(st, nz(c.getRemainingPrincipal()), Long::sum);
    }

    // 살아 있는 채권 = 상각·종료 제외
    int activeCount = countByStatus.get(ContractStatus.NORMAL)
        + countByStatus.get(ContractStatus.OVERDUE)
        + countByStatus.get(ContractStatus.ACCELERATED);
    long outstanding = principalByStatus.get(ContractStatus.NORMAL)
        + principalByStatus.get(ContractStatus.OVERDUE)
        + principalByStatus.get(ContractStatus.ACCELERATED);

    // 연체 지표에는 기한이익상실(해지)도 포함한다. 실질적으로 회수가 막힌 채권이다.
    int overdueCount = countByStatus.get(ContractStatus.OVERDUE) + countByStatus.get(ContractStatus.ACCELERATED);
    long overduePrincipal = principalByStatus.get(ContractStatus.OVERDUE)
        + principalByStatus.get(ContractStatus.ACCELERATED);

    double ratio = outstanding > 0 ? (overduePrincipal * 100.0 / outstanding) : 0.0;

    List<LoanPortfolioResponse.StatusRow> rows = new ArrayList<>();
    countByStatus.forEach((st, cnt) -> rows.add(LoanPortfolioResponse.StatusRow.builder()
        .status(st).count(cnt).principal(principalByStatus.get(st)).build()));

    return LoanPortfolioResponse.builder()
        .activeCount(activeCount)
        .outstandingPrincipal(outstanding)
        .overdueCount(overdueCount)
        .overduePrincipal(overduePrincipal)
        .overdueRatio(round1(ratio))
        .writtenOffCount(countByStatus.get(ContractStatus.WRITTEN_OFF))
        .writtenOffAmount(principalByStatus.get(ContractStatus.WRITTEN_OFF))
        .byStatus(rows)
        .build();
  }

  /**
   * 연체 경과기간 분포.
   * 한 채권에 여러 연체 회차가 있으면 가장 오래된 회차를 기준으로 구간을 정하고,
   * 금액은 그 채권의 미납 합계를 쓴다. 회차 단위로 세면 건수가 부풀려진다.
   */
  @Transactional(readOnly = true)
  public OverdueAgingResponse overdueAging() {
    List<OverdueRowResponse> rows = overdueService.overdueList();

    Map<String, Integer> maxDays = new HashMap<>();
    Map<String, Long> sumAmount = new HashMap<>();
    for (OverdueRowResponse r : rows) {
      String cn = r.getContractNumber();
      if (cn == null) continue;
      int days = r.getOverdueDays() == null ? 0 : r.getOverdueDays();
      maxDays.merge(cn, days, Math::max);
      sumAmount.merge(cn, r.getUnpaidAmount() == null ? 0L : r.getUnpaidAmount(), Long::sum);
    }

    int n = AGING_LABELS.length;
    int[] counts = new int[n];
    long[] amounts = new long[n];

    for (Map.Entry<String, Integer> e : maxDays.entrySet()) {
      int idx = bucketIndex(e.getValue());
      counts[idx]++;
      amounts[idx] += sumAmount.getOrDefault(e.getKey(), 0L);
    }

    long total = 0L;
    for (long a : amounts) total += a;

    List<OverdueAgingResponse.Bucket> buckets = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      buckets.add(OverdueAgingResponse.Bucket.builder()
          .label(AGING_LABELS[i])
          .count(counts[i])
          .amount(amounts[i])
          .ratio(total > 0 ? round1(amounts[i] * 100.0 / total) : 0.0)
          .build());
    }

    return OverdueAgingResponse.builder()
        .totalCount(maxDays.size())
        .totalAmount(total)
        .buckets(buckets)
        .build();
  }

  /**
   * 이번 달 회수 예정액 — 납입예정일이 이번 달인 회차의 미납 잔액.
   * 청구중지(기한이익상실) 회차는 개별 청구 대상이 아니므로 뺀다.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> monthlyDue(LocalDate baseDate) {
    LocalDate base = baseDate != null ? baseDate : LocalDate.now();
    LocalDate from = base.withDayOfMonth(1);
    LocalDate to = base.withDayOfMonth(base.lengthOfMonth());

    List<String> numbers = contractRepo.findAllActiveWithCustomer().stream()
        .map(Contract::getContractNumber)
        .filter(Objects::nonNull)
        .toList();
    if (numbers.isEmpty()) {
      return Map.of("from", from, "to", to, "dueAmount", 0L, "paidAmount", 0L, "count", 0);
    }

    long due = 0L, paid = 0L;
    int count = 0;
    for (PaymentSchedule ps : scheduleRepo.findByContractNumberIn(numbers)) {
      if (PaymentSchedule.LINE_SUSPENDED.equals(ps.getLineStatus())) continue;
      LocalDate d = ps.getPaymentDate() != null ? ps.getPaymentDate() : ps.getTaxInvoiceDate();
      if (d == null || d.isBefore(from) || d.isAfter(to)) continue;
      due += ps.dueTotal();
      paid += ps.paidTotal();
      count++;
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("from", from);
    out.put("to", to);
    out.put("count", count);
    out.put("dueAmount", due);
    out.put("paidAmount", paid);
    out.put("unpaidAmount", Math.max(0L, due - paid));
    out.put("collectionRate", due > 0 ? round1(paid * 100.0 / due) : 0.0);
    return out;
  }

  // ── 보조 ────────────────────────────────────────────────────

  /** 채권현황 화면과 동일한 상태 판정 규칙 */
  private String derive(Contract c, Set<String> overdueNumbers) {
    String stored = c.getStatus();
    if (stored != null && ContractStatus.STORED.contains(stored)) return stored;

    boolean overdue = overdueNumbers.contains(c.getContractNumber());
    if (!overdue && c.getEndDate() != null && c.getEndDate().isBefore(LocalDate.now())) {
      return ContractStatus.CLOSED;
    }
    return overdue ? ContractStatus.OVERDUE : ContractStatus.NORMAL;
  }

  private static int bucketIndex(int days) {
    for (int i = 0; i < AGING_BOUNDS.length; i++) {
      if (days <= AGING_BOUNDS[i]) return i;
    }
    return AGING_BOUNDS.length;
  }

  private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
  private static long nz(Long v) { return v == null ? 0L : v; }
}
