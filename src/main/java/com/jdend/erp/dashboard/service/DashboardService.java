package com.jdend.erp.dashboard.service;

import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.CustomerRepository;
import com.jdend.erp.dashboard.dto.*;
import com.jdend.erp.dashboard.repository.*;
import com.jdend.erp.myinfo.entity.BankAccount;
import com.jdend.erp.myinfo.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DashboardService {

  private final DashboardBankTransactionRepository bankTxRepo;
  private final DashboardVoucherRepository voucherRepo;
  private final BankAccountRepository bankAccountRepo;
  private final MaturityDashboardRepository maturityRepo;
  private final ReceivableDashboardRepository receivableRepo;
  private final VoucherRepository voucherRepository;
  private final ContractRepository contractRepository;
  private final CustomerRepository customerRepository;

  public DashboardCashResponse cashDaily(LocalDate baseDate) {
    LocalDate d = (baseDate != null) ? baseDate : LocalDate.now().minusDays(1);

    long opening = nz(bankTxRepo.sumNetBefore(d));
    long dep = nz(bankTxRepo.sumDepositOn(d));
    long wit = nz(bankTxRepo.sumWithdrawalOn(d));
    long closing = opening + dep - wit;

    return DashboardCashResponse.builder()
        .baseDate(d)
        .openingBalance(opening)
        .todayDeposit(dep)
        .todayWithdrawal(wit)
        .closingBalance(closing)
        .build();
  }

  public List<DashboardMaturityRow> maturitySoon(int days, int limit) {
    LocalDate today = LocalDate.now();
    LocalDate until = today.plusDays(days);

    List<DashboardMaturityRow> rows = maturityRepo.findMaturitySoon(today, until, limit);
    for (DashboardMaturityRow r : rows) {
      r.setDday(r.getEndDate() == null ? 0 : ChronoUnit.DAYS.between(today, r.getEndDate()));
    }
    return rows;
  }

  public List<DashboardReceivableRow> receivablesTop(int limit) {
    return receivableRepo.findTopReceivables(limit);
  }

  public List<DashboardBankSummaryRow> bankSummary() {
    LocalDate today = LocalDate.now();
    LocalDate yesterday = today.minusDays(1);

    List<BankAccount> accounts = bankAccountRepo.findByIsActiveTrueOrderByIdAsc();

    // 계좌 미등록 시 기존 전표합산 단일 행 유지
    if (accounts.isEmpty()) {
      long prevBal  = nz(voucherRepo.sumNetUpToByAccountCode(yesterday));
      long todayDep = nz(voucherRepo.sumDebitOnByAccountCode(today));
      long todayWit = nz(voucherRepo.sumCreditOnByAccountCode(today));
      return List.of(DashboardBankSummaryRow.builder()
          .bankName("보통예금")
          .accountNumber("")
          .accountAlias("전표합산")
          .balance2DaysAgo(prevBal)
          .yesterdayDeposit(todayDep)
          .yesterdayWithdrawal(todayWit)
          .currentBalance(prevBal + todayDep - todayWit)
          .build());
    }

    // 보통예금 전표 라인 전체 조회 — description 필드에 계좌번호 포함 여부로 매칭
    List<Object[]> prevLines  = voucherRepo.findAllBankLinesUpTo(yesterday);
    List<Object[]> todayLines = voucherRepo.findAllBankLinesOn(today);

    Map<Long, Long> openingMap = new LinkedHashMap<>();
    Map<Long, Long> debitMap   = new LinkedHashMap<>();
    Map<Long, Long> creditMap  = new LinkedHashMap<>();
    for (BankAccount a : accounts) {
      openingMap.put(a.getId(), 0L);
      debitMap.put(a.getId(), 0L);
      creditMap.put(a.getId(), 0L);
    }

    for (Object[] row : prevLines) {
      String lineType = (String) row[0];
      long amount = toLong(row[1]);
      String desc = (String) row[2];
      long delta = "DEBIT".equals(lineType) ? amount : -amount;
      BankAccount matched = matchBankAccount(desc, accounts);
      if (matched != null) openingMap.merge(matched.getId(), delta, Long::sum);
    }

    for (Object[] row : todayLines) {
      String lineType = (String) row[0];
      long amount = toLong(row[1]);
      String desc = (String) row[2];
      BankAccount matched = matchBankAccount(desc, accounts);
      if (matched != null) {
        if ("DEBIT".equals(lineType)) debitMap.merge(matched.getId(), amount, Long::sum);
        else                           creditMap.merge(matched.getId(), amount, Long::sum);
      }
    }

    List<DashboardBankSummaryRow> result = new ArrayList<>();
    for (BankAccount acc : accounts) {
      long op  = openingMap.get(acc.getId());
      long dep = debitMap.get(acc.getId());
      long crd = creditMap.get(acc.getId());
      String label = acc.getAccountAlias() != null && !acc.getAccountAlias().isBlank()
          ? acc.getAccountAlias() : acc.getBankName();
      result.add(DashboardBankSummaryRow.builder()
          .bankName(acc.getBankName())
          .accountNumber(acc.getAccountNumber())
          .accountAlias(label)
          .balance2DaysAgo(op)
          .yesterdayDeposit(dep)
          .yesterdayWithdrawal(crd)
          .currentBalance(op + dep - crd)
          .build());
    }
    return result;
  }

  private BankAccount matchBankAccount(String description, List<BankAccount> accounts) {
    if (description == null || description.isBlank()) return null;
    for (BankAccount acc : accounts) {
      String accNo = acc.getAccountNumber();
      if (accNo != null && !accNo.isBlank() && description.contains(accNo)) return acc;
    }
    return null;
  }

  public List<DashboardBankDiffRow> bankVoucherDiff() {
    LocalDate to = LocalDate.now().minusDays(1);
    LocalDate from = to.minusDays(13); // 최근 14일

    // 은행내역 일별 합계
    List<Object[]> bankRows = bankTxRepo.sumByDateRange(from, to);
    Map<LocalDate, long[]> bankMap = new LinkedHashMap<>();
    for (Object[] row : bankRows) {
      LocalDate d = ((java.sql.Date) row[0]).toLocalDate();
      long dep = toLong(row[1]);
      long wit = toLong(row[2]);
      bankMap.put(d, new long[]{dep, wit});
    }

    // 전표내역 일별 합계
    List<Object[]> voucherRows = voucherRepo.sumBankVoucherByDateRange(from, to);
    Map<LocalDate, long[]> voucherMap = new LinkedHashMap<>();
    for (Object[] row : voucherRows) {
      LocalDate d = ((java.sql.Date) row[0]).toLocalDate();
      long debit  = toLong(row[1]);
      long credit = toLong(row[2]);
      voucherMap.put(d, new long[]{debit, credit});
    }

    // 차이가 있는 날짜만 수집
    Set<LocalDate> allDates = new LinkedHashSet<>();
    allDates.addAll(bankMap.keySet());
    allDates.addAll(voucherMap.keySet());

    List<DashboardBankDiffRow> result = new ArrayList<>();
    for (LocalDate d : allDates) {
      long[] b = bankMap.getOrDefault(d, new long[]{0, 0});
      long[] v = voucherMap.getOrDefault(d, new long[]{0, 0});
      long depDiff = b[0] - v[0];
      long witDiff = b[1] - v[1];
      if (depDiff != 0 || witDiff != 0) {
        result.add(DashboardBankDiffRow.builder()
            .txDate(d)
            .bankDeposit(b[0])
            .bankWithdrawal(b[1])
            .voucherDeposit(v[0])
            .voucherWithdrawal(v[1])
            .depositDiff(depDiff)
            .withdrawalDiff(witDiff)
            .build());
      }
    }
    result.sort(Comparator.comparing(DashboardBankDiffRow::getTxDate).reversed());
    return result;
  }

  private long toLong(Object o) {
    if (o == null) return 0L;
    if (o instanceof Long) return (Long) o;
    if (o instanceof BigDecimal) return ((BigDecimal) o).longValue();
    if (o instanceof Number) return ((Number) o).longValue();
    return 0L;
  }

  private long nz(Long v) {
    return v == null ? 0L : v;
  }


  public DashboardPendingVoucherResponse pendingVoucherSummary() {
    List<Voucher> pending = voucherRepository.searchForApproval(null, "대기");
    int count = pending.size();
    List<DashboardPendingVoucherResponse.Row> recent = pending.stream()
        .limit(5)
        .map(v -> DashboardPendingVoucherResponse.Row.builder()
            .id(v.getId())
            .voucherNo(v.getVoucherNo())
            .voucherDate(v.getVoucherDate())
            .totalAmount(v.getTotalAmount())
            .memo(v.getMemo())
            .build())
        .toList();
    return DashboardPendingVoucherResponse.builder()
        .count(count)
        .recent(recent)
        .build();
  }

  // ✅ 차량번호 normalize
  private String normalize(String v) {
    if (v == null) return null;
    return v.replace(" ", "").replace("-", "").trim();
  }
}