package com.jdend.erp.accounting.cash.service;

import com.jdend.erp.accounting.cash.dto.*;
import com.jdend.erp.accounting.cash.repository.BankTransactionRepository;
import com.jdend.erp.accounting.cash.repository.VoucherCashAggRepository;
import com.jdend.erp.myinfo.entity.BankAccount;
import com.jdend.erp.myinfo.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DailyCashService {

  private final BankTransactionRepository bankRepo;
  private final VoucherCashAggRepository voucherCashRepo;
  private final BankAccountRepository bankAccountRepo;

  // 현금성 계정 키워드(원하면 여기 수정)
  private static final String[] CASH_KEYS = {"현금","예금","보통예금","은행","국민","하나","신한"};

  @Transactional(readOnly = true)
  public DailyCashFlowMonthResponse month(String ym) {
    if (ym == null || ym.isBlank()) throw new IllegalArgumentException("month(YYYY-MM)은 필수입니다.");

    YearMonth yearMonth = YearMonth.parse(ym);
    LocalDate start = yearMonth.atDay(1);
    LocalDate end = yearMonth.atEndOfMonth();

    // 1) 은행 집계
    Map<LocalDate, long[]> bankMap = new HashMap<>(); // [0]=in, [1]=out
    for (var r : bankRepo.sumByDay(start, end)) {
      bankMap.put(r.getTxDate(), new long[]{nz(r.getInAmt()), nz(r.getOutAmt())});
    }

    // 2) 전표(현금성 계정만) 집계
    Map<LocalDate, long[]> voucherMap = new HashMap<>(); // [0]=in(DEBIT), [1]=out(CREDIT)
    List<VoucherCashAggRepository.DayCashSumRow> vRows =
        voucherCashRepo.sumCashByDay(start, end,
            CASH_KEYS[0], CASH_KEYS[1], CASH_KEYS[2], CASH_KEYS[3], CASH_KEYS[4], CASH_KEYS[5], CASH_KEYS[6]);

    for (var r : vRows) {
      LocalDate d = r.getVoucherDate();
      long amt = nz(r.getAmt());
      long[] arr = voucherMap.computeIfAbsent(d, k -> new long[]{0,0});
      if ("DEBIT".equalsIgnoreCase(r.getLineType())) arr[0] += amt;
      else arr[1] += amt;
    }

    // 3) 날짜별 full row 생성 (월의 모든 날짜)
    List<DailyCashFlowRowResponse> out = new ArrayList<>();
    long tBankIn=0, tBankOut=0, tVouIn=0, tVouOut=0, tDiffIn=0, tDiffOut=0;

    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
      long[] b = bankMap.getOrDefault(d, new long[]{0,0});
      long[] v = voucherMap.getOrDefault(d, new long[]{0,0});

      long diffIn = b[0] - v[0];
      long diffOut = b[1] - v[1];

      tBankIn += b[0];
      tBankOut += b[1];
      tVouIn += v[0];
      tVouOut += v[1];
      tDiffIn += diffIn;
      tDiffOut += diffOut;

      out.add(DailyCashFlowRowResponse.builder()
          .date(d)
          .bankIncome(b[0])
          .bankExpense(b[1])
          .voucherIncome(v[0])
          .voucherExpense(v[1])
          .diffIncome(diffIn)
          .diffExpense(diffOut)
          .build());
    }

    return DailyCashFlowMonthResponse.builder()
        .rows(out)
        .totalBankIncome(tBankIn)
        .totalBankExpense(tBankOut)
        .totalVoucherIncome(tVouIn)
        .totalVoucherExpense(tVouOut)
        .totalDiffIncome(tDiffIn)
        .totalDiffExpense(tDiffOut)
        .build();
  }

  @Transactional(readOnly = true)
  public DailyFundReportResponse daily(LocalDate date) {
    if (date == null) throw new IllegalArgumentException("date는 필수입니다.");

    // 전일잔액 (기준일 이전 누적, 통장별)
    Map<String, long[]> prevMap = new HashMap<>(); // bankName → [cumIn, cumOut]
    for (var r : bankRepo.sumCumulativeByBankBefore(date)) {
      prevMap.put(r.getBankName(), new long[]{nz(r.getInAmt()), nz(r.getOutAmt())});
    }

    // 당일 통장별 입출금
    Map<String, long[]> todayMap = new HashMap<>();
    for (var r : bankRepo.sumByBankOn(date)) {
      todayMap.put(r.getBankName(), new long[]{nz(r.getInAmt()), nz(r.getOutAmt())});
    }

    // 통장 이름 전체 집합 (전일에만 있거나 당일에만 있어도 모두 포함)
    Set<String> bankNames = new LinkedHashSet<>();
    bankNames.addAll(prevMap.keySet());
    bankNames.addAll(todayMap.keySet());

    List<DailyFundReportResponse.BankRow> banks = new ArrayList<>();
    long bankIn=0, bankOut=0, totalPrev=0, totalBalance=0;

    for (String bankName : bankNames) {
      long[] prev  = prevMap.getOrDefault(bankName, new long[]{0,0});
      long[] today = todayMap.getOrDefault(bankName, new long[]{0,0});

      long prevBalance = prev[0] - prev[1];
      long inAmt  = today[0];
      long outAmt = today[1];
      long balance = prevBalance + inAmt - outAmt;

      bankIn       += inAmt;
      bankOut      += outAmt;
      totalPrev    += prevBalance;
      totalBalance += balance;

      banks.add(DailyFundReportResponse.BankRow.builder()
          .bankName(bankName)
          .prevBalance(prevBalance)
          .income(inAmt)
          .expense(outAmt)
          .balance(balance)
          .build());
    }

    // 전표(현금성 계정만, 계정별 합계) — banks 비어있을 때 fallback보다 먼저 조회
    List<VoucherCashAggRepository.AccountCashSumRow> vRows =
        voucherCashRepo.sumCashByAccountOn(date,
            CASH_KEYS[0], CASH_KEYS[1], CASH_KEYS[2], CASH_KEYS[3], CASH_KEYS[4], CASH_KEYS[5], CASH_KEYS[6]);

    // 은행 CSV 없을 때: 전표 기준 보통예금(100101) 잔액으로 Section I 대체 (계좌별 분리)
    if (banks.isEmpty()) {
      List<BankAccount> accounts = bankAccountRepo.findByIsActiveTrueOrderByIdAsc();

      if (accounts.isEmpty()) {
        // 계좌 미등록 시 전체 합산 단일 행
        long prevBal = nz(voucherCashRepo.sumNetBefore100101(date));
        long todayIn = 0, todayOut = 0;
        for (var r : vRows) {
          if ("보통예금".equals(r.getAccountName())) {
            long amt = nz(r.getAmt());
            if ("DEBIT".equalsIgnoreCase(r.getLineType())) todayIn  += amt;
            else                                            todayOut += amt;
          }
        }
        long balance = prevBal + todayIn - todayOut;
        bankIn = todayIn; bankOut = todayOut;
        totalPrev = prevBal; totalBalance = balance;
        banks.add(DailyFundReportResponse.BankRow.builder()
            .bankName("보통예금 (전표합산)")
            .prevBalance(prevBal).income(todayIn).expense(todayOut).balance(balance)
            .build());
      } else {
        // 계좌별 분리 — description의 계좌번호로 라인 매칭
        List<Object[]> prevLines  = voucherCashRepo.findBankLinesBeforeDate(date);
        List<Object[]> todayLines = voucherCashRepo.findBankLinesOnDate(date);

        Map<Long, Long> openingMap = new LinkedHashMap<>();
        Map<Long, Long> debitMap   = new LinkedHashMap<>();
        Map<Long, Long> creditMap  = new LinkedHashMap<>();
        for (BankAccount a : accounts) {
          openingMap.put(a.getId(), 0L);
          debitMap.put(a.getId(), 0L);
          creditMap.put(a.getId(), 0L);
        }

        // 어느 통장인지 알 수 없는 라인을 모아 둔다.
        // 엑셀로 일괄 업로드한 전표처럼 적요에 계좌번호가 없으면 여기로 들어간다.
        // 전에는 그냥 버려서 자금일보에서 금액이 통째로 사라졌다.
        long noneOpening = 0L, noneDebit = 0L, noneCredit = 0L;

        for (Object[] row : prevLines) {
          String lt = (String) row[0]; long amt = toLong(row[1]); String desc = (String) row[2];
          BankAccount m = matchBankAccount(desc, accounts);
          long signed = "DEBIT".equals(lt) ? amt : -amt;
          if (m != null) openingMap.merge(m.getId(), signed, Long::sum);
          else           noneOpening += signed;
        }
        for (Object[] row : todayLines) {
          String lt = (String) row[0]; long amt = toLong(row[1]); String desc = (String) row[2];
          BankAccount m = matchBankAccount(desc, accounts);
          boolean in = "DEBIT".equals(lt);
          if (m != null) {
            if (in) debitMap.merge(m.getId(), amt, Long::sum);
            else    creditMap.merge(m.getId(), amt, Long::sum);
          } else {
            if (in) noneDebit += amt;
            else    noneCredit += amt;
          }
        }

        for (BankAccount acc : accounts) {
          long op  = openingMap.get(acc.getId());
          long dep = debitMap.get(acc.getId());
          long crd = creditMap.get(acc.getId());
          long bal = op + dep - crd;
          String label = acc.getAccountAlias() != null && !acc.getAccountAlias().isBlank()
              ? acc.getAccountAlias() + " (" + acc.getBankName() + ")"
              : acc.getBankName();
          bankIn += dep; bankOut += crd; totalPrev += op; totalBalance += bal;
          banks.add(DailyFundReportResponse.BankRow.builder()
              .bankName(label).prevBalance(op).income(dep).expense(crd).balance(bal)
              .build());
        }

        // 통장을 가려내지 못한 몫은 따로 한 줄로 보여준다.
        // 합계에는 넣어야 전표 총액과 자금일보가 맞는다.
        if (noneOpening != 0 || noneDebit != 0 || noneCredit != 0) {
          long bal = noneOpening + noneDebit - noneCredit;
          bankIn += noneDebit; bankOut += noneCredit;
          totalPrev += noneOpening; totalBalance += bal;
          banks.add(DailyFundReportResponse.BankRow.builder()
              .bankName("통장없음")
              .prevBalance(noneOpening).income(noneDebit).expense(noneCredit).balance(bal)
              .build());
        }
      }
    }

    List<DailyFundReportResponse.VoucherRow> vIn = new ArrayList<>();
    List<DailyFundReportResponse.VoucherRow> vOut = new ArrayList<>();
    long vInSum=0, vOutSum=0;

    for (var r : vRows) {
      long amt = nz(r.getAmt());
      String name = r.getAccountName() == null ? "" : r.getAccountName();
      DailyFundReportResponse.VoucherRow row = DailyFundReportResponse.VoucherRow.builder()
          .accountCode("").accountName(name).amount(amt).memo("").build();
      if ("DEBIT".equalsIgnoreCase(r.getLineType())) { vIn.add(row); vInSum += amt; }
      else { vOut.add(row); vOutSum += amt; }
    }

    // 전표 전체 계정(II. 일일 자금 세부내역 — 모든 전표)
    List<DailyFundReportResponse.VoucherRow> allDebits  = new ArrayList<>();
    List<DailyFundReportResponse.VoucherRow> allCredits = new ArrayList<>();
    long allDebitSum=0, allCreditSum=0;

    for (var r : voucherCashRepo.sumAllByAccountOn(date)) {
      long amt = nz(r.getAmt());
      String name = r.getAccountName() == null ? "" : r.getAccountName();
      DailyFundReportResponse.VoucherRow row = DailyFundReportResponse.VoucherRow.builder()
          .accountCode("").accountName(name).amount(amt).memo("").build();
      if ("DEBIT".equalsIgnoreCase(r.getLineType())) { allDebits.add(row);  allDebitSum  += amt; }
      else                                            { allCredits.add(row); allCreditSum += amt; }
    }

    return DailyFundReportResponse.builder()
        .banks(banks)
        .bankPrevBalanceTotal(totalPrev)
        .bankIncomeTotal(bankIn)
        .bankExpenseTotal(bankOut)
        .bankBalanceTotal(totalBalance)
        .voucherIncomes(vIn)
        .voucherIncomeTotal(vInSum)
        .voucherExpenses(vOut)
        .voucherExpenseTotal(vOutSum)
        .incomeDiff(bankIn - vInSum)
        .expenseDiff(bankOut - vOutSum)
        .allVoucherDebits(allDebits)
        .allVoucherDebitTotal(allDebitSum)
        .allVoucherCredits(allCredits)
        .allVoucherCreditTotal(allCreditSum)
        .build();
  }

  // ==========================
  // III/IV. 월별 자금 현황
  // ==========================
  @Transactional(readOnly = true)
  public MonthlyFundReportResponse monthly(String ym) {
    if (ym == null || ym.isBlank()) throw new IllegalArgumentException("month(YYYY-MM)은 필수입니다.");

    YearMonth yearMonth = YearMonth.parse(ym);
    LocalDate monthStart = yearMonth.atDay(1);
    LocalDate monthEnd   = yearMonth.atEndOfMonth();

    // 전월잔액 (해당 월 1일 이전 누적, 통장별)
    Map<String, long[]> prevMap = new HashMap<>();
    for (var r : bankRepo.sumCumulativeByBankBefore(monthStart)) {
      prevMap.put(r.getBankName(), new long[]{nz(r.getInAmt()), nz(r.getOutAmt())});
    }

    // 해당 월 통장별 합계
    Map<String, long[]> monthMap = new HashMap<>();
    for (var r : bankRepo.sumByBankBetween(monthStart, monthEnd)) {
      monthMap.put(r.getBankName(), new long[]{nz(r.getInAmt()), nz(r.getOutAmt())});
    }

    Set<String> bankNames = new LinkedHashSet<>();
    bankNames.addAll(prevMap.keySet());
    bankNames.addAll(monthMap.keySet());

    List<MonthlyFundReportResponse.MonthBankRow> banks = new ArrayList<>();
    long totalPrev=0, totalIn=0, totalOut=0, totalBalance=0;

    for (String bankName : bankNames) {
      long[] prev  = prevMap.getOrDefault(bankName, new long[]{0,0});
      long[] month = monthMap.getOrDefault(bankName, new long[]{0,0});

      long prevBalance = prev[0] - prev[1];
      long income  = month[0];
      long expense = month[1];
      long balance = prevBalance + income - expense;

      totalPrev    += prevBalance;
      totalIn      += income;
      totalOut     += expense;
      totalBalance += balance;

      banks.add(MonthlyFundReportResponse.MonthBankRow.builder()
          .bankName(bankName)
          .prevBalance(prevBalance)
          .income(income)
          .expense(expense)
          .balance(balance)
          .build());
    }

    // IV. 월별 세부내역 (전표 현금성 계정, 계정별 집계) — 기존 호환 유지
    List<MonthlyFundReportResponse.VoucherRow> mvIn  = new ArrayList<>();
    List<MonthlyFundReportResponse.VoucherRow> mvOut = new ArrayList<>();
    long mvInSum=0, mvOutSum=0;

    for (var r : voucherCashRepo.sumCashByAccountBetween(monthStart, monthEnd,
        CASH_KEYS[0], CASH_KEYS[1], CASH_KEYS[2], CASH_KEYS[3], CASH_KEYS[4], CASH_KEYS[5], CASH_KEYS[6])) {
      long amt = nz(r.getAmt());
      String name = r.getAccountName() == null ? "" : r.getAccountName();
      if ("DEBIT".equalsIgnoreCase(r.getLineType())) {
        mvIn.add(MonthlyFundReportResponse.VoucherRow.builder().accountName(name).amount(amt).build());
        mvInSum += amt;
      } else {
        mvOut.add(MonthlyFundReportResponse.VoucherRow.builder().accountName(name).amount(amt).build());
        mvOutSum += amt;
      }
    }

    // 전체 계정(IV. 월별 자금 세부내역 — 모든 전표)
    List<MonthlyFundReportResponse.VoucherRow> allMvDebits  = new ArrayList<>();
    List<MonthlyFundReportResponse.VoucherRow> allMvCredits = new ArrayList<>();
    long allMvDebitSum=0, allMvCreditSum=0;

    for (var r : voucherCashRepo.sumAllByAccountBetween(monthStart, monthEnd)) {
      long amt = nz(r.getAmt());
      String name = r.getAccountName() == null ? "" : r.getAccountName();
      if ("DEBIT".equalsIgnoreCase(r.getLineType())) {
        allMvDebits.add(MonthlyFundReportResponse.VoucherRow.builder().accountName(name).amount(amt).build());
        allMvDebitSum += amt;
      } else {
        allMvCredits.add(MonthlyFundReportResponse.VoucherRow.builder().accountName(name).amount(amt).build());
        allMvCreditSum += amt;
      }
    }

    return MonthlyFundReportResponse.builder()
        .banks(banks)
        .totalPrevBalance(totalPrev)
        .totalIncome(totalIn)
        .totalExpense(totalOut)
        .totalBalance(totalBalance)
        .voucherIncomes(mvIn)
        .voucherIncomeTotal(mvInSum)
        .voucherExpenses(mvOut)
        .voucherExpenseTotal(mvOutSum)
        .allVoucherDebits(allMvDebits)
        .allVoucherDebitTotal(allMvDebitSum)
        .allVoucherCredits(allMvCredits)
        .allVoucherCreditTotal(allMvCreditSum)
        .build();
  }

  private long nz(Long v){ return v == null ? 0L : v; }

  private long toLong(Object o) {
    if (o == null) return 0L;
    if (o instanceof Long l) return l;
    if (o instanceof Number n) return n.longValue();
    return 0L;
  }

  // BUG-10 수정: 짧은 계좌번호가 긴 계좌번호를 포함한 문자열에 오매칭되는 문제 방지
  // 계좌번호를 길이 내림차순으로 정렬 후 매칭하여 더 구체적인(긴) 번호가 먼저 체크되도록 함
  private BankAccount matchBankAccount(String description, List<BankAccount> accounts) {
    if (description == null || description.isBlank()) return null;
    return accounts.stream()
        .filter(a -> a.getAccountNumber() != null && !a.getAccountNumber().isBlank())
        .sorted(Comparator.comparingInt(a -> -a.getAccountNumber().length()))
        .filter(a -> description.contains(a.getAccountNumber()))
        .findFirst().orElse(null);
  }
}