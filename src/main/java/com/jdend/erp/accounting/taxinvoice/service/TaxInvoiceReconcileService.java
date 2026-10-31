package com.jdend.erp.accounting.taxinvoice.service;

import com.jdend.erp.accounting.statements.repository.StatementAggRepository;
import com.jdend.erp.accounting.taxinvoice.dto.ReconcileResponse;
import com.jdend.erp.accounting.taxinvoice.entity.TaxInvoice;
import com.jdend.erp.accounting.taxinvoice.repository.TaxInvoiceRepository;
import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import com.jdend.erp.payment.banktx.entity.BankTransaction;
import com.jdend.erp.payment.banktx.repository.PaymentBankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 세금계산서 ↔ 통장 입출금 ↔ 전표, 셋을 맞대어 빠진 것을 찾는다.
 *
 * <p>찾는 것은 네 가지다.
 * <ul>
 *   <li><b>미수금</b> — 매출 세금계산서를 발행했는데 들어온 돈이 없다</li>
 *   <li><b>미지급금</b> — 매입 세금계산서를 받았는데 나간 돈이 없다</li>
 *   <li><b>매출 세금계산서 없음</b> — 돈은 들어왔는데 발행한 세금계산서가 없다</li>
 *   <li><b>매입 세금계산서 없음</b> — 돈은 나갔는데 받은 세금계산서가 없다</li>
 * </ul>
 * 여기에 세금계산서가 전표에 분개되었는지를 줄마다 함께 표시한다.
 *
 * <p><b>맞대는 방법</b> — 금액이 같고, 날짜가 허용범위 안이고, 통장 적요에 거래처명이
 * 들어 있으면 한 쌍으로 본다. 날짜가 가까운 것부터 짝을 지으며 한 번 쓴 건은 다시 쓰지
 * 않는다. 세금계산서를 끊은 달과 돈이 들어오는 달이 다른 일이 흔해서 범위를 넉넉히 둔다
 * (기본 90일).
 *
 * <p>금액이 정확히 같은 것만 짝으로 보므로, 여러 건을 한 번에 결제하거나 쪼개어 보낸
 * 거래는 짝을 찾지 못해 양쪽에 모두 남는다. 그것도 확인해야 할 거래이므로 숨기지 않고
 * 그대로 보여준다.
 */
@Service
@RequiredArgsConstructor
public class TaxInvoiceReconcileService {

  private final TaxInvoiceRepository invoiceRepo;
  private final PaymentBankTransactionRepository bankRepo;
  private final StatementAggRepository voucherRepo;

  public static final String UNCOLLECTED = "미수금";
  public static final String UNPAID = "미지급금";
  public static final String NO_SALES_INVOICE = "매출 세금계산서 없음";
  public static final String NO_PURCHASE_INVOICE = "매입 세금계산서 없음";

  /**
   * @param dayWindow   세금계산서 작성일과 입출금일 사이 허용 일수
   * @param matchByName 통장 적요에 거래처명이 들어 있어야 짝으로 볼지
   * @param minAmount   이 금액 미만의 통장 거래는 무시한다(수수료·이자 등 잡거래 제외용)
   */
  @Transactional(readOnly = true)
  public ReconcileResponse reconcile(LocalDate startDate, LocalDate endDate,
                                     int dayWindow, boolean matchByName, long minAmount) {
    if (startDate == null || endDate == null) {
      throw new IllegalArgumentException("조회 기간을 지정해주세요.");
    }
    if (endDate.isBefore(startDate)) {
      throw new IllegalArgumentException("종료일이 시작일보다 이전입니다.");
    }
    int window = dayWindow <= 0 ? 90 : dayWindow;

    // 세금계산서는 기간 그대로, 통장은 짝을 찾을 수 있게 앞뒤로 넓혀 읽는다.
    List<TaxInvoice> sales = dropCancelled(
        invoiceRepo.findForReconcile(TaxInvoice.SALES, startDate, endDate));
    List<TaxInvoice> purchases = dropCancelled(
        invoiceRepo.findForReconcile(TaxInvoice.PURCHASE, startDate, endDate));
    List<BankTransaction> bank = bankRepo.search("", "",
        startDate.minusDays(window), endDate.plusDays(window));

    List<Slot> deposits = new ArrayList<>();
    List<Slot> withdrawals = new ArrayList<>();
    for (BankTransaction t : bank) {
      long in = nz(t.getDepositAmount());
      long out = nz(t.getWithdrawalAmount());
      String memo = join(t.getSummary(), t.getRemarks());
      if (in >= minAmount && in > 0) deposits.add(new Slot(t.getTxDate(), in, memo));
      if (out >= minAmount && out > 0) withdrawals.add(new Slot(t.getTxDate(), out, memo));
    }

    List<VoucherLine> lines = voucherRepo.findLinesBetween(
        startDate.minusDays(window), endDate.plusDays(window), "");

    List<ReconcileResponse.Row> rows = new ArrayList<>();

    // ① 매출 세금계산서 ↔ 입금
    for (TaxInvoice inv : sales) {
      Slot hit = take(deposits, inv, window, matchByName);
      boolean voucher = hasVoucher(lines, inv, window, matchByName);
      if (hit == null) {
        rows.add(row(UNCOLLECTED, inv, null, voucher,
            "매출 세금계산서를 발행했는데 들어온 돈이 없습니다."));
      } else if (!voucher) {
        rows.add(row("전표 없음", inv, hit, false,
            "세금계산서와 입금은 맞는데 분개한 전표가 없습니다."));
      }
    }

    // ② 매입 세금계산서 ↔ 출금
    for (TaxInvoice inv : purchases) {
      Slot hit = take(withdrawals, inv, window, matchByName);
      boolean voucher = hasVoucher(lines, inv, window, matchByName);
      if (hit == null) {
        rows.add(row(UNPAID, inv, null, voucher,
            "매입 세금계산서를 받았는데 나간 돈이 없습니다."));
      } else if (!voucher) {
        rows.add(row("전표 없음", inv, hit, false,
            "세금계산서와 출금은 맞는데 분개한 전표가 없습니다."));
      }
    }

    // ③ 짝을 못 찾고 남은 입금 — 매출 세금계산서가 없는 돈
    for (Slot s : deposits) {
      if (s.used) continue;
      if (s.date.isBefore(startDate) || s.date.isAfter(endDate)) continue;
      rows.add(bankRow(NO_SALES_INVOICE, s,
          "돈은 들어왔는데 맞는 매출 세금계산서가 없습니다."));
    }

    // ④ 짝을 못 찾고 남은 출금 — 매입 세금계산서가 없는 돈
    for (Slot s : withdrawals) {
      if (s.used) continue;
      if (s.date.isBefore(startDate) || s.date.isAfter(endDate)) continue;
      rows.add(bankRow(NO_PURCHASE_INVOICE, s,
          "돈은 나갔는데 맞는 매입 세금계산서가 없습니다."));
    }

    rows.sort(Comparator
        .comparing(ReconcileResponse.Row::getType)
        .thenComparing(ReconcileResponse.Row::getDate));

    // 유형별 묶음
    Map<String, long[]> agg = new LinkedHashMap<>();
    for (ReconcileResponse.Row r : rows) {
      long[] v = agg.computeIfAbsent(r.getType(), k -> new long[2]);
      v[0]++;
      v[1] += r.getAmount();
    }
    List<ReconcileResponse.Summary> summary = new ArrayList<>();
    for (Map.Entry<String, long[]> e : agg.entrySet()) {
      summary.add(ReconcileResponse.Summary.builder()
          .type(e.getKey())
          .note(noteOf(e.getKey()))
          .count((int) e.getValue()[0])
          .amount(e.getValue()[1])
          .build());
    }

    return ReconcileResponse.builder()
        .startDate(startDate).endDate(endDate)
        .dayWindow(window).matchByName(matchByName)
        .summary(summary).rows(rows)
        .build();
  }

  /**
   * 수정세금계산서로 취소된 건은 짝 맞추기에서 뺀다.
   *
   * <p>홈택스 목록에는 취소·감액이 금액 음수로 나온다. 음수만 빼면 취소된 원래
   * 세금계산서가 '돈이 안 들어왔다'며 미수금으로 올라오므로, 같은 거래처·같은
   * 금액의 양수 한 건과 함께 뺀다. 짝이 없는 음수는 그대로 둔다 — 그것도 봐야 한다.
   */
  private List<TaxInvoice> dropCancelled(List<TaxInvoice> list) {
    List<TaxInvoice> out = new ArrayList<>(list);
    for (TaxInvoice neg : list) {
      if (nz(neg.getTotalAmount()) >= 0) continue;
      long want = -nz(neg.getTotalAmount());
      TaxInvoice hit = null;
      for (TaxInvoice pos : out) {
        if (nz(pos.getTotalAmount()) != want) continue;
        if (!normalizeName(pos.getPartnerName()).equals(normalizeName(neg.getPartnerName()))) continue;
        hit = pos;
        break;
      }
      if (hit != null) {
        out.remove(hit);
        out.remove(neg);
      }
    }
    return out;
  }

  // ── 짝 맞추기 ────────────────────────────────────────────

  /** 날짜가 가까운 것부터 하나 집어 쓴 것으로 표시한다. 없으면 null. */
  private Slot take(List<Slot> pool, TaxInvoice inv, int window, boolean matchByName) {
    Slot best = null;
    long bestGap = Long.MAX_VALUE;
    for (Slot s : pool) {
      if (s.used) continue;
      if (s.amount != nz(inv.getTotalAmount())) continue;
      long gap = Math.abs(ChronoUnit.DAYS.between(inv.getIssueDate(), s.date));
      if (gap > window) continue;
      if (matchByName && !nameMatches(inv.getPartnerName(), s.memo)) continue;
      if (gap < bestGap) { best = s; bestGap = gap; }
    }
    if (best != null) best.used = true;
    return best;
  }

  /** 금액이 같고 적요에 거래처명이 들어 있는 전표 줄이 있는지. */
  private boolean hasVoucher(List<VoucherLine> lines, TaxInvoice inv, int window, boolean matchByName) {
    long total = nz(inv.getTotalAmount());
    long supply = nz(inv.getSupplyAmount());
    for (VoucherLine l : lines) {
      long amt = nz(l.getAmount());
      if (amt != total && amt != supply) continue;
      LocalDate d = l.getVoucher() == null ? null : l.getVoucher().getVoucherDate();
      if (d == null) continue;
      if (Math.abs(ChronoUnit.DAYS.between(inv.getIssueDate(), d)) > window) continue;
      if (matchByName && !nameMatches(inv.getPartnerName(), l.getDescription())) continue;
      return true;
    }
    return false;
  }

  /**
   * 거래처명 맞대기.
   *
   * <p>통장 적요에는 '주식회사' 나 괄호가 빠지고 이름이 잘려 찍히는 일이 흔하다
   * ('지에스렌트카주식회사' 가 '지에스렌트카주식회' 로 찍힌다). 그래서 상호에서
   * 법인 표기와 공백을 떼고, 한쪽이 다른 쪽에 들어가면 같은 거래처로 본다.
   */
  static boolean nameMatches(String partner, String memo) {
    String a = normalizeName(partner);
    String b = normalizeName(memo);
    if (a.length() < 2 || b.isEmpty()) return false;
    if (b.contains(a) || a.contains(b)) return true;
    // 이름이 잘린 경우 — 앞 네 글자가 겹치면 같은 곳으로 본다.
    int n = Math.min(4, a.length());
    return n >= 2 && b.contains(a.substring(0, n));
  }

  private static String normalizeName(String v) {
    if (v == null) return "";
    return v.replaceAll("주식회사|\\(주\\)|㈜|유한회사|합자회사|사업자|\\s+|[()\\[\\]\\-_.]", "")
        .trim();
  }

  private static String noteOf(String type) {
    return switch (type) {
      case UNCOLLECTED -> "받을 돈 — 세금계산서는 나갔고 입금이 없다";
      case UNPAID -> "줄 돈 — 세금계산서는 받았고 출금이 없다";
      case NO_SALES_INVOICE -> "매출 누락 의심 — 입금에 맞는 세금계산서가 없다";
      case NO_PURCHASE_INVOICE -> "매입 증빙 없음 의심 — 출금에 맞는 세금계산서가 없다";
      case "전표 없음" -> "분개 누락 — 세금계산서와 돈은 맞는데 전표가 없다";
      default -> "";
    };
  }

  // ── 행 만들기 ────────────────────────────────────────────

  private ReconcileResponse.Row row(String type, TaxInvoice inv, Slot bank, boolean voucher, String note) {
    return ReconcileResponse.Row.builder()
        .type(type)
        .date(inv.getIssueDate())
        .partnerName(inv.getPartnerName())
        .amount(nz(inv.getTotalAmount()))
        .hasInvoice(true)
        .hasBankTx(bank != null)
        .hasVoucher(voucher)
        .invoiceId(inv.getId())
        .invoiceType(inv.getInvoiceType())
        .bankSummary(bank == null ? null : bank.memo)
        .bankDate(bank == null ? null : bank.date)
        .note(note)
        .build();
  }

  private ReconcileResponse.Row bankRow(String type, Slot s, String note) {
    return ReconcileResponse.Row.builder()
        .type(type)
        .date(s.date)
        .partnerName(s.memo)
        .amount(s.amount)
        .hasInvoice(false)
        .hasBankTx(true)
        .hasVoucher(false)
        .bankSummary(s.memo)
        .bankDate(s.date)
        .note(note)
        .build();
  }

  private static final class Slot {
    final LocalDate date;
    final long amount;
    final String memo;
    boolean used;

    Slot(LocalDate date, long amount, String memo) {
      this.date = date;
      this.amount = amount;
      this.memo = memo == null ? "" : memo;
    }
  }

  private static String join(String a, String b) {
    String x = a == null ? "" : a.trim();
    String y = b == null ? "" : b.trim();
    if (x.isEmpty()) return y;
    if (y.isEmpty()) return x;
    return x + " " + y;
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
