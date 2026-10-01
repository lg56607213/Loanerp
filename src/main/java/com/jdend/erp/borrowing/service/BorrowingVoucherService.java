package com.jdend.erp.borrowing.service;

import com.jdend.erp.accounting.settings.service.OtherAccountSettingsService;
import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import com.jdend.erp.accounting.voucher.service.AccountResolver;
import com.jdend.erp.accounting.voucher.service.VoucherNumberService;
import com.jdend.erp.borrowing.entity.Borrowing;
import com.jdend.erp.borrowing.entity.BorrowingTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 차입금 전표 생성.
 *
 * 계정은 기타계정관리 > '차입금 개시 전표' / '차입금상환 계정분류' 설정을 따른다.
 * 그 화면은 예전부터 있었지만 쓰는 곳이 없었다 — 이 서비스가 처음 쓰는 곳이다.
 *
 * 설정이 비어 있으면 전표를 거르지 않고 기본 계정으로 만든다.
 * 수납 전표는 설정이 없으면 건너뛰지만, 차입금은 건수가 적고 금액이 커서
 * 전표가 조용히 빠지면 재무상태표의 부채가 통째로 비어 보인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BorrowingVoucherService {

  /** 보통예금 — 돈이 들어오고 나가는 자리 */
  public static final String DEFAULT_BANK_CODE = "100101";
  /** 차입금 — 부채 */
  public static final String DEFAULT_BORROWING_CODE = "200201";
  /** 이자비용 */
  public static final String DEFAULT_INTEREST_EXPENSE_CODE = "500101";

  private final OtherAccountSettingsService accountSettings;
  private final AccountResolver accountResolver;
  private final VoucherNumberService voucherNumberService;
  private final VoucherRepository voucherRepository;

  /**
   * 차입 개시 전표 — (차) 보통예금 / (대) 차입금.
   *
   * @return 만든 전표 id
   */
  @Transactional
  public Long createOpeningVoucher(Borrowing b) {
    long amount = nz(b.getPrincipalAmount());
    if (amount <= 0) return null;

    LocalDate date = b.getBorrowDate() != null ? b.getBorrowDate() : LocalDate.now();
    String memo = "차입금 " + b.getBorrowingNumber() + " " + nvl(b.getLenderName()) + " 차입";

    Voucher v = newVoucher(date, amount, memo);

    String debitDesc = "차입금 입금";
    if (notBlank(b.getDepositAccount())) debitDesc += " [" + b.getDepositAccount() + "]";

    addLine(v, "DEBIT",
        accountSettings.getLoanOpenDebitAccount(), DEFAULT_BANK_CODE,
        amount, debitDesc, 1);

    addLine(v, "CREDIT",
        accountSettings.getLoanOpenCreditAccount(), DEFAULT_BORROWING_CODE,
        amount, nvl(b.getLenderName()) + " 차입", 2);

    return voucherRepository.save(v).getId();
  }

  /**
   * 상환 전표 — (차) 차입금 + 이자비용 / (대) 보통예금.
   *
   * 원금만 갚았거나 이자만 냈으면 그 줄만 넣는다. 금액이 0인 줄을 그대로 두면
   * 전표에 의미 없는 0원 라인이 남는다.
   */
  @Transactional
  public Long createRepaymentVoucher(Borrowing b, BorrowingTransaction tx) {
    long principal = nz(tx.getPrincipalAmount());
    long interest  = nz(tx.getInterestAmount());
    long total = principal + interest;
    if (total <= 0) return null;

    LocalDate date = tx.getTxDate() != null ? tx.getTxDate() : LocalDate.now();
    String memo = "차입금 " + b.getBorrowingNumber() + " " + nvl(b.getLenderName())
                + " " + describe(principal, interest);

    Voucher v = newVoucher(date, total, memo);

    int order = 1;
    if (principal > 0) {
      addLine(v, "DEBIT",
          accountSettings.getLoanDebit1Account(), DEFAULT_BORROWING_CODE,
          principal, "차입금 원금상환", order++);
    }
    if (interest > 0) {
      addLine(v, "DEBIT",
          accountSettings.getLoanDebit2Account(), DEFAULT_INTEREST_EXPENSE_CODE,
          interest, interestDescription(tx), order++);
    }

    String creditDesc = "차입금 상환 출금";
    if (notBlank(tx.getWithdrawAccount())) creditDesc += " [" + tx.getWithdrawAccount() + "]";
    addLine(v, "CREDIT",
        accountSettings.getLoanCreditAccount(), DEFAULT_BANK_CODE,
        total, creditDesc, order);

    return voucherRepository.save(v).getId();
  }

  /**
   * 차입금 전표를 지운다.
   *
   * 승인 여부는 부르는 쪽(BorrowingService)에서 VoucherApprovalGuard 로 먼저 막는다.
   * 여기까지 왔다는 건 지워도 되는 전표라는 뜻이다.
   */
  @Transactional
  public void deleteVoucher(Long voucherId) {
    if (voucherId == null) return;
    voucherRepository.findById(voucherId).ifPresent(voucherRepository::delete);
  }

  /** 이자 줄의 적요에 계산 기간을 남긴다. 나중에 금액이 맞는지 따질 때 근거가 된다. */
  private String interestDescription(BorrowingTransaction tx) {
    if (tx.getInterestFrom() == null || tx.getInterestTo() == null) return "차입금 이자지급";
    return "차입금 이자지급 (" + tx.getInterestFrom() + " ~ " + tx.getInterestTo() + ")";
  }

  private String describe(long principal, long interest) {
    if (principal > 0 && interest > 0) return "원금·이자 상환";
    if (principal > 0) return "원금상환";
    return "이자지급";
  }

  private Voucher newVoucher(LocalDate date, long amount, String memo) {
    return Voucher.builder()
        .voucherNo(voucherNumberService.next(date))
        .voucherDate(date)
        .totalAmount(amount)
        .status("대기")
        .memo(memo)
        .build();
  }

  /**
   * 설정된 계정명으로 코드를 찾고, 설정이 비었거나 찾지 못하면 기본 코드를 쓴다.
   * 계정코드가 비면 재무제표 집계에서 통째로 빠지기 때문에 반드시 채운다.
   */
  private void addLine(Voucher v, String lineType, String settingName, String fallbackCode,
                       long amount, String description, int sortOrder) {
    AccountResolver.Resolved acc = notBlank(settingName)
        ? accountResolver.resolve(null, settingName)
        : accountResolver.resolve(fallbackCode, null);

    if (acc.code() == null) {
      log.warn("차입금 전표: 계정 '{}' 의 코드를 찾지 못해 기본 계정({})을 씁니다.", settingName, fallbackCode);
      acc = accountResolver.resolve(fallbackCode, null);
    }

    v.addLine(VoucherLine.builder()
        .lineType(lineType)
        .accountCode(acc.code())
        .accountName(acc.name())
        .amount(amount)
        .description(description)
        .sortOrder(sortOrder)
        .build());
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
  private static String nvl(String s) { return s == null ? "" : s; }
  private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
