package com.jdend.erp.borrowing.service;

import com.jdend.erp.accounting.voucher.service.VoucherApprovalGuard;
import com.jdend.erp.borrowing.dto.*;
import com.jdend.erp.borrowing.entity.Borrowing;
import com.jdend.erp.borrowing.entity.BorrowingSchedule;
import com.jdend.erp.borrowing.entity.BorrowingTransaction;
import com.jdend.erp.borrowing.repository.BorrowingRepository;
import com.jdend.erp.borrowing.repository.BorrowingScheduleRepository;
import com.jdend.erp.borrowing.repository.BorrowingTransactionRepository;
import com.jdend.erp.borrowing.support.BorrowingConstants;
import com.jdend.erp.borrowing.support.BorrowingScheduleGenerator;
import com.jdend.erp.contract.entity.PaymentDayType;
import com.jdend.erp.contract.support.DailyInterestCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 차입금 등록·조회·상환·이자지급 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BorrowingService {

  private static final DateTimeFormatter NUM_PREFIX = DateTimeFormatter.ofPattern("yyMMdd");

  private final BorrowingRepository borrowingRepo;
  private final BorrowingTransactionRepository txRepo;
  private final BorrowingScheduleRepository scheduleRepo;
  private final BorrowingVoucherService voucherService;
  private final VoucherApprovalGuard voucherApprovalGuard;

  // ── 조회 ──────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<BorrowingResponse> list(String keyword) {
    String kw = keyword == null ? "" : keyword.trim();
    return borrowingRepo.search(kw).stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public BorrowingResponse get(String borrowingNumber) {
    return toResponse(mustFind(borrowingNumber));
  }

  @Transactional(readOnly = true)
  public List<BorrowingTransactionResponse> transactions(String borrowingNumber) {
    Borrowing b = mustFind(borrowingNumber);

    // 잔액은 날짜순으로 흘려야 나오므로 오름차순으로 계산한 뒤 뒤집어 보여준다.
    List<BorrowingTransaction> asc = txRepo.findByBorrowingNumberOrderByTxDateAscIdAsc(borrowingNumber);
    List<BorrowingTransactionResponse> out = new ArrayList<>(asc.size());

    long balance = nz(b.getPrincipalAmount());
    for (BorrowingTransaction t : asc) {
      balance -= nz(t.getPrincipalAmount());
      out.add(BorrowingTransactionResponse.builder()
          .id(t.getId())
          .borrowingNumber(t.getBorrowingNumber())
          .txDate(t.getTxDate())
          .principalAmount(nz(t.getPrincipalAmount()))
          .interestAmount(nz(t.getInterestAmount()))
          .totalAmount(t.total())
          .interestFrom(t.getInterestFrom())
          .interestTo(t.getInterestTo())
          .withdrawAccount(t.getWithdrawAccount())
          .installmentNo(t.getInstallmentNo())
          .memo(t.getMemo())
          .voucherId(t.getVoucherId())
          .balanceAfter(balance)
          .build());
    }
    java.util.Collections.reverse(out);
    return out;
  }

  /** 약정 스케줄 + 그 회차로 등록된 실제 지급액 */
  @Transactional(readOnly = true)
  public List<BorrowingScheduleRowResponse> schedule(String borrowingNumber) {
    mustFind(borrowingNumber);
    List<BorrowingSchedule> rows = scheduleRepo.findByBorrowingNumberOrderByInstallmentNoAsc(borrowingNumber);
    List<BorrowingTransaction> txs = txRepo.findByBorrowingNumberOrderByTxDateAscIdAsc(borrowingNumber);
    LocalDate today = LocalDate.now();

    List<BorrowingScheduleRowResponse> out = new ArrayList<>(rows.size());
    for (BorrowingSchedule s : rows) {
      long paid = txs.stream()
          .filter(t -> s.getInstallmentNo().equals(t.getInstallmentNo()))
          .mapToLong(BorrowingTransaction::total)
          .sum();
      long scheduled = s.total();
      long unpaid = Math.max(0L, scheduled - paid);

      String status;
      if (paid >= scheduled && scheduled > 0) status = "완납";
      else if (paid > 0)                      status = "부분지급";
      else if (!s.getDueDate().isAfter(today)) status = "미지급";
      else                                     status = "예정";

      out.add(BorrowingScheduleRowResponse.builder()
          .installmentNo(s.getInstallmentNo())
          .dueDate(s.getDueDate())
          .principalAmount(nz(s.getPrincipalAmount()))
          .interestAmount(nz(s.getInterestAmount()))
          .totalAmount(scheduled)
          .balance(nz(s.getBalance()))
          .paidAmount(paid)
          .unpaidAmount(unpaid)
          .lineStatus(status)
          .build());
    }
    return out;
  }

  // ── 등록 ──────────────────────────────────────────────────

  @Transactional
  public BorrowingResponse create(BorrowingRequest req) {
    validate(req);

    Borrowing b = Borrowing.builder()
        .borrowingNumber(nextNumber(req.getBorrowDate()))
        .lenderName(req.getLenderName().trim())
        .borrowingType(blankToNull(req.getBorrowingType()))
        .lenderContact(blankToNull(req.getLenderContact()))
        .principalAmount(req.getPrincipalAmount())
        .borrowDate(req.getBorrowDate())
        .maturityDate(req.getMaturityDate())
        .interestRate(req.getInterestRate())
        .repaymentMethod(req.getRepaymentMethod())
        .installmentCount(req.getInstallmentCount())
        .paymentDay(req.getPaymentDay())
        .paymentDayType(PaymentDayType.orDefault(req.getPaymentDayType()))
        .depositAccount(blankToNull(req.getDepositAccount()))
        .status(BorrowingConstants.NORMAL)
        .remainingPrincipal(req.getPrincipalAmount())
        .remarks(blankToNull(req.getRemarks()))
        .build();

    Borrowing saved = borrowingRepo.save(b);

    List<BorrowingSchedule> schedule = BorrowingScheduleGenerator.generate(saved);
    if (!schedule.isEmpty()) scheduleRepo.saveAll(schedule);

    if (!Boolean.FALSE.equals(req.getCreateVoucher())) {
      saved.setVoucherId(voucherService.createOpeningVoucher(saved));
    }
    return toResponse(saved);
  }

  @Transactional
  public BorrowingResponse update(Long id, BorrowingRequest req) {
    Borrowing b = borrowingRepo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("차입금을 찾을 수 없습니다: " + id));
    validate(req);

    // 상환 내역이 붙은 뒤에 조건을 바꾸면 이미 계산된 이자·잔액과 어긋난다.
    boolean hasTx = txRepo.existsByBorrowingNumber(b.getBorrowingNumber());
    if (hasTx && !b.getPrincipalAmount().equals(req.getPrincipalAmount())) {
      throw new IllegalStateException(
          "상환·이자지급 내역이 있어 차입원금을 바꿀 수 없습니다."
        + " 내역을 먼저 삭제한 뒤 수정하세요.");
    }
    voucherApprovalGuard.requireNotApproved(b.getVoucherId(), "차입금 수정");

    b.setLenderName(req.getLenderName().trim());
    b.setBorrowingType(blankToNull(req.getBorrowingType()));
    b.setLenderContact(blankToNull(req.getLenderContact()));
    b.setPrincipalAmount(req.getPrincipalAmount());
    b.setBorrowDate(req.getBorrowDate());
    b.setMaturityDate(req.getMaturityDate());
    b.setInterestRate(req.getInterestRate());
    b.setRepaymentMethod(req.getRepaymentMethod());
    b.setInstallmentCount(req.getInstallmentCount());
    b.setPaymentDay(req.getPaymentDay());
    b.setPaymentDayType(PaymentDayType.orDefault(req.getPaymentDayType()));
    b.setDepositAccount(blankToNull(req.getDepositAccount()));
    b.setRemarks(blankToNull(req.getRemarks()));

    // 약정이 바뀌면 스케줄은 새로 만든다. 실제 지급 내역은 그대로 둔다.
    scheduleRepo.deleteByBorrowingNumber(b.getBorrowingNumber());
    List<BorrowingSchedule> schedule = BorrowingScheduleGenerator.generate(b);
    if (!schedule.isEmpty()) scheduleRepo.saveAll(schedule);

    recomputeBalance(b);
    return toResponse(b);
  }

  @Transactional
  public void delete(Long id) {
    Borrowing b = borrowingRepo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("차입금을 찾을 수 없습니다: " + id));

    if (txRepo.existsByBorrowingNumber(b.getBorrowingNumber())) {
      throw new IllegalStateException(
          "이 차입금에는 상환·이자지급 내역이 있어 삭제할 수 없습니다."
        + " 내역을 먼저 모두 취소한 뒤 삭제하세요.");
    }
    voucherApprovalGuard.requireNotApproved(b.getVoucherId(), "차입금");

    if (b.getVoucherId() != null) voucherService.deleteVoucher(b.getVoucherId());
    scheduleRepo.deleteByBorrowingNumber(b.getBorrowingNumber());
    borrowingRepo.deleteById(id);
  }

  // ── 상환 · 이자지급 ────────────────────────────────────────

  /**
   * 이자 자동계산 — 직전 이자지급일(없으면 차입일)부터 지급일까지, 미상환 원금에 일할.
   *
   * 사용자가 고칠 수 있는 '제안값'이다. 실제 이체액이 다르면 그 금액으로 등록한다.
   */
  @Transactional(readOnly = true)
  public InterestPreviewResponse previewInterest(String borrowingNumber, LocalDate asOf) {
    Borrowing b = mustFind(borrowingNumber);
    LocalDate to = asOf != null ? asOf : LocalDate.now();
    LocalDate from = lastInterestDate(b);

    long balance = balanceAsOf(b, to);
    long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(from, to));
    long interest = DailyInterestCalculator.accrued(balance, b.getInterestRate(), from, to);

    String basis = String.format("%s ~ %s (%d일) · 잔액 %,d원 × 연 %s%% ÷ 365",
        from, to, days, balance,
        b.getInterestRate() == null ? "0" : b.getInterestRate().stripTrailingZeros().toPlainString());

    return InterestPreviewResponse.builder()
        .borrowingNumber(borrowingNumber)
        .from(from).to(to)
        .days((int) days)
        .balance(balance)
        .interestAmount(interest)
        .basis(basis)
        .build();
  }

  @Transactional
  public BorrowingTransactionResponse addTransaction(BorrowingTransactionRequest req) {
    if (req == null || isBlank(req.getBorrowingNumber())) {
      throw new IllegalArgumentException("차입번호를 입력하세요.");
    }
    Borrowing b = mustFind(req.getBorrowingNumber().trim());

    if (req.getTxDate() == null) throw new IllegalArgumentException("지급일을 입력하세요.");

    long principal = nz(req.getPrincipalAmount());
    long interest  = nz(req.getInterestAmount());
    if (principal < 0 || interest < 0) throw new IllegalArgumentException("금액은 0보다 작을 수 없습니다.");
    if (principal + interest <= 0) {
      throw new IllegalArgumentException("상환원금이나 지급이자 중 하나는 입력해야 합니다.");
    }
    if (req.getTxDate().isBefore(b.getBorrowDate())) {
      throw new IllegalArgumentException(
          "지급일(" + req.getTxDate() + ")이 차입일(" + b.getBorrowDate() + ")보다 앞설 수 없습니다.");
    }

    long remaining = nz(b.getRemainingPrincipal());
    if (principal > remaining) {
      throw new IllegalArgumentException(String.format(
          "상환원금 %,d원이 미상환 원금 %,d원보다 많습니다.", principal, remaining));
    }

    BorrowingTransaction tx = BorrowingTransaction.builder()
        .borrowingNumber(b.getBorrowingNumber())
        .txDate(req.getTxDate())
        .principalAmount(principal)
        .interestAmount(interest)
        .interestFrom(interest > 0 ? req.getInterestFrom() : null)
        .interestTo(interest > 0 ? req.getInterestTo() : null)
        .withdrawAccount(blankToNull(req.getWithdrawAccount()))
        .installmentNo(req.getInstallmentNo())
        .memo(blankToNull(req.getMemo()))
        .build();

    BorrowingTransaction saved = txRepo.save(tx);

    if (!Boolean.FALSE.equals(req.getCreateVoucher())) {
      saved.setVoucherId(voucherService.createRepaymentVoucher(b, saved));
    }
    recomputeBalance(b);

    return transactions(b.getBorrowingNumber()).stream()
        .filter(r -> r.getId().equals(saved.getId()))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public void deleteTransaction(Long txId) {
    BorrowingTransaction tx = txRepo.findById(txId)
        .orElseThrow(() -> new IllegalArgumentException("상환 내역을 찾을 수 없습니다: " + txId));

    voucherApprovalGuard.requireNotApproved(tx.getVoucherId(), "차입금 상환");

    if (tx.getVoucherId() != null) voucherService.deleteVoucher(tx.getVoucherId());
    txRepo.deleteById(txId);

    borrowingRepo.findByBorrowingNumber(tx.getBorrowingNumber()).ifPresent(this::recomputeBalance);
  }

  // ── 내부 ──────────────────────────────────────────────────

  /**
   * 미상환 원금을 등록된 내역으로 다시 합산한다.
   *
   * 들고 있는 값을 더하고 빼는 대신 매번 전부 다시 더한다.
   * 중간에 한 건을 지워도 숫자가 어긋나지 않는다.
   */
  private void recomputeBalance(Borrowing b) {
    long repaid = txRepo.findByBorrowingNumberOrderByTxDateAscIdAsc(b.getBorrowingNumber())
        .stream().mapToLong(t -> nz(t.getPrincipalAmount())).sum();

    long remaining = Math.max(0L, nz(b.getPrincipalAmount()) - repaid);
    b.setRemainingPrincipal(remaining);
    b.setStatus(remaining == 0 ? BorrowingConstants.CLOSED : BorrowingConstants.NORMAL);
  }

  /** 그 날짜까지의 상환을 반영한 미상환 원금 */
  private long balanceAsOf(Borrowing b, LocalDate asOf) {
    long repaid = txRepo.findByBorrowingNumberOrderByTxDateAscIdAsc(b.getBorrowingNumber())
        .stream()
        .filter(t -> !t.getTxDate().isAfter(asOf))
        .mapToLong(t -> nz(t.getPrincipalAmount()))
        .sum();
    return Math.max(0L, nz(b.getPrincipalAmount()) - repaid);
  }

  /** 이자 기산일 — 마지막으로 이자를 낸 날, 없으면 차입일 */
  private LocalDate lastInterestDate(Borrowing b) {
    return txRepo.findByBorrowingNumberOrderByTxDateAscIdAsc(b.getBorrowingNumber())
        .stream()
        .filter(t -> nz(t.getInterestAmount()) > 0)
        .map(t -> t.getInterestTo() != null ? t.getInterestTo() : t.getTxDate())
        .reduce((first, second) -> second)        // 마지막 값
        .orElse(b.getBorrowDate());
  }

  private void validate(BorrowingRequest req) {
    if (req == null) throw new IllegalArgumentException("요청값이 없습니다.");
    if (isBlank(req.getLenderName())) throw new IllegalArgumentException("차입처를 입력하세요.");
    if (req.getPrincipalAmount() == null || req.getPrincipalAmount() <= 0) {
      throw new IllegalArgumentException("차입원금을 입력하세요.");
    }
    if (req.getBorrowDate() == null) throw new IllegalArgumentException("차입일을 입력하세요.");

    if (req.getMaturityDate() != null && req.getMaturityDate().isBefore(req.getBorrowDate())) {
      throw new IllegalArgumentException("만기일이 차입일보다 앞설 수 없습니다.");
    }
    if (!BorrowingConstants.REPAYMENT_METHODS.contains(req.getRepaymentMethod())) {
      throw new IllegalArgumentException("상환방식을 선택하세요. (만기일시 / 원금균등 / 원리금균등 / 수시상환)");
    }
    // 차입금 이자율에는 대부업법 최고이자율을 적용하지 않는다.
    // 그 상한은 빌려주는 쪽에 걸리는 것이고, 여기서는 회사가 빌리는 쪽이다.
    if (req.getInterestRate() != null
        && (req.getInterestRate().compareTo(BigDecimal.ZERO) < 0
         || req.getInterestRate().compareTo(BigDecimal.valueOf(100)) > 0)) {
      throw new IllegalArgumentException("이자율은 0 ~ 100% 사이로 입력하세요.");
    }
    if (BorrowingConstants.hasSchedule(req.getRepaymentMethod())) {
      if (req.getInstallmentCount() == null || req.getInstallmentCount() <= 0) {
        throw new IllegalArgumentException("분할 회차수를 입력하세요.");
      }
      if (req.getInstallmentCount() > 600) {
        throw new IllegalArgumentException("회차수가 너무 큽니다. (최대 600회)");
      }
      if (!PaymentDayType.isLastDay(req.getPaymentDayType())
          && (req.getPaymentDay() == null || req.getPaymentDay() < 1 || req.getPaymentDay() > 31)) {
        throw new IllegalArgumentException("납입일자를 선택하세요.");
      }
    }
  }

  /** 차입번호 — B + yyMMdd + 3자리. 같은 날 여러 건이면 뒤 번호가 올라간다. */
  private String nextNumber(LocalDate date) {
    String prefix = "B" + date.format(NUM_PREFIX);
    String max = borrowingRepo.findMaxNumberByPrefix(prefix);
    int seq = 1;
    if (max != null && max.length() > prefix.length()) {
      try {
        seq = Integer.parseInt(max.substring(prefix.length())) + 1;
      } catch (NumberFormatException ignored) {
        // 사람이 손으로 넣은 번호가 섞였을 수 있다. 그러면 1부터 다시 찾는다.
      }
    }
    String candidate = prefix + String.format("%03d", seq);
    while (borrowingRepo.existsByBorrowingNumber(candidate)) {
      seq++;
      candidate = prefix + String.format("%03d", seq);
    }
    return candidate;
  }

  private Borrowing mustFind(String borrowingNumber) {
    return borrowingRepo.findByBorrowingNumber(borrowingNumber)
        .orElseThrow(() -> new IllegalArgumentException("차입금을 찾을 수 없습니다: " + borrowingNumber));
  }

  private BorrowingResponse toResponse(Borrowing b) {
    List<BorrowingTransaction> txs =
        txRepo.findByBorrowingNumberOrderByTxDateAscIdAsc(b.getBorrowingNumber());

    long repaid = txs.stream().mapToLong(t -> nz(t.getPrincipalAmount())).sum();
    long interest = txs.stream().mapToLong(t -> nz(t.getInterestAmount())).sum();

    return BorrowingResponse.builder()
        .id(b.getId())
        .borrowingNumber(b.getBorrowingNumber())
        .lenderName(b.getLenderName())
        .borrowingType(b.getBorrowingType())
        .lenderContact(b.getLenderContact())
        .principalAmount(nz(b.getPrincipalAmount()))
        .borrowDate(b.getBorrowDate())
        .maturityDate(b.getMaturityDate())
        .interestRate(b.getInterestRate())
        .repaymentMethod(b.getRepaymentMethod())
        .installmentCount(b.getInstallmentCount())
        .paymentDay(b.getPaymentDay())
        .paymentDayType(b.getPaymentDayType())
        .depositAccount(b.getDepositAccount())
        .status(b.getStatus())
        .remainingPrincipal(nz(b.getRemainingPrincipal()))
        .voucherId(b.getVoucherId())
        .remarks(b.getRemarks())
        .repaidPrincipal(repaid)
        .paidInterest(interest)
        .transactionCount(txs.size())
        .build();
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
  private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
  private static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }
}
