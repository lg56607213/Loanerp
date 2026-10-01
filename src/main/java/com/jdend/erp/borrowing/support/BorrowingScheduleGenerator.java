package com.jdend.erp.borrowing.support;

import com.jdend.erp.borrowing.entity.Borrowing;
import com.jdend.erp.borrowing.entity.BorrowingSchedule;
import com.jdend.erp.contract.entity.PaymentDayType;
import com.jdend.erp.loan.interest.AnnualRate;
import com.jdend.erp.loan.interest.EqualPaymentScheduleGenerator;
import com.jdend.erp.loan.interest.ScheduledInstallment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 차입금 약정 상환스케줄 생성.
 *
 * 대출(빌려준 돈)과 계산식은 같다. 원리금균등은 기존 계산기를 그대로 쓴다 —
 * 같은 식을 두 벌 두면 한쪽만 고쳐져 금액이 갈린다.
 */
public final class BorrowingScheduleGenerator {

  private static final int SCALE = 16;
  private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

  /**
   * 약정대로 회차를 만든다. 스케줄이 없는 방식(수시상환)이면 빈 목록.
   *
   * 첫 납입일은 차입일 다음 달의 지정일이다. 차입한 그 달에 바로 한 달치 이자를
   * 내는 경우는 없으므로 한 달 뒤부터 시작한다.
   */
  public static List<BorrowingSchedule> generate(Borrowing b) {
    if (b == null) return List.of();
    String method = b.getRepaymentMethod();
    if (!BorrowingConstants.hasSchedule(method)) return List.of();

    int count = b.getInstallmentCount() == null ? 0 : b.getInstallmentCount();
    long principal = b.getPrincipalAmount() == null ? 0L : b.getPrincipalAmount();
    if (count <= 0 || principal <= 0 || b.getBorrowDate() == null) return List.of();

    BigDecimal ratePercent = b.getInterestRate() == null ? BigDecimal.ZERO : b.getInterestRate();
    LocalDate first = firstDueDate(b);

    return switch (method) {
      case BorrowingConstants.EQUAL_INSTALLMENT -> equalInstallment(b, principal, ratePercent, count, first);
      case BorrowingConstants.EQUAL_PRINCIPAL   -> equalPrincipal(b, principal, ratePercent, count, first);
      case BorrowingConstants.BULLET            -> bullet(b, principal, ratePercent, count, first);
      default -> List.of();
    };
  }

  /** 차입일 다음 달의 납입일. '말일'이면 그 달의 마지막 날. */
  static LocalDate firstDueDate(Borrowing b) {
    LocalDate base = b.getBorrowDate().plusMonths(1);
    return applyPaymentDay(base, b.getPaymentDayType(), b.getPaymentDay());
  }

  /**
   * 그 달의 납입일을 구한다.
   *
   * 지정일이 그 달에 없으면(2월 31일) 말일로 당긴다. 날짜가 없다고 건너뛰면
   * 회차가 빠지고, 다음 달로 미루면 회차가 겹친다.
   */
  static LocalDate applyPaymentDay(LocalDate month, String dayType, Integer day) {
    int last = month.lengthOfMonth();
    if (PaymentDayType.isLastDay(dayType)) return month.withDayOfMonth(last);
    if (day == null || day < 1) return month;
    return month.withDayOfMonth(Math.min(day, last));
  }

  /** 납입일 규칙을 지키며 n개월 뒤의 납입일을 구한다. */
  private static LocalDate dueAt(Borrowing b, LocalDate first, int monthsAfter) {
    LocalDate base = first.withDayOfMonth(1).plusMonths(monthsAfter);
    return applyPaymentDay(base, b.getPaymentDayType(), b.getPaymentDay());
  }

  /** 원리금균등 — 기존 대출 계산기를 그대로 쓴다. */
  private static List<BorrowingSchedule> equalInstallment(
      Borrowing b, long principal, BigDecimal ratePercent, int count, LocalDate first) {

    List<ScheduledInstallment> rows =
        EqualPaymentScheduleGenerator.generate(principal, AnnualRate.ofPercent(ratePercent), count, first);

    List<BorrowingSchedule> out = new ArrayList<>(rows.size());
    for (ScheduledInstallment r : rows) {
      out.add(row(b, r.installmentNo(),
          dueAt(b, first, r.installmentNo() - 1),
          r.scheduledPrincipal(), r.scheduledInterest(), r.closingPrincipal()));
    }
    return out;
  }

  /** 원금균등 — 매회 같은 원금, 이자는 남은 잔액에 붙어 줄어든다. */
  private static List<BorrowingSchedule> equalPrincipal(
      Borrowing b, long principal, BigDecimal ratePercent, int count, LocalDate first) {

    BigDecimal monthlyRate = monthlyRate(ratePercent);
    long each = principal / count;

    List<BorrowingSchedule> out = new ArrayList<>(count);
    long remaining = principal;
    for (int no = 1; no <= count; no++) {
      long interest = monthlyInterest(remaining, monthlyRate);
      // 마지막 회차가 나눗셈 나머지를 가져가 잔액이 정확히 0이 된다
      long part = (no == count) ? remaining : Math.min(each, remaining);
      remaining -= part;
      out.add(row(b, no, dueAt(b, first, no - 1), part, interest, remaining));
    }
    return out;
  }

  /** 만기일시 — 이자만 내다가 마지막 회차에 원금을 한 번에 갚는다. */
  private static List<BorrowingSchedule> bullet(
      Borrowing b, long principal, BigDecimal ratePercent, int count, LocalDate first) {

    BigDecimal monthlyRate = monthlyRate(ratePercent);
    long interest = monthlyInterest(principal, monthlyRate);

    List<BorrowingSchedule> out = new ArrayList<>(count);
    for (int no = 1; no <= count; no++) {
      boolean lastOne = (no == count);
      long part = lastOne ? principal : 0L;
      long balance = lastOne ? 0L : principal;
      out.add(row(b, no, dueAt(b, first, no - 1), part, interest, balance));
    }
    return out;
  }

  private static BigDecimal monthlyRate(BigDecimal annualPercent) {
    if (annualPercent == null || annualPercent.signum() <= 0) return BigDecimal.ZERO;
    return annualPercent
        .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP)
        .divide(TWELVE, SCALE, RoundingMode.HALF_UP);
  }

  private static long monthlyInterest(long balance, BigDecimal monthlyRate) {
    if (balance <= 0 || monthlyRate.signum() <= 0) return 0L;
    return BigDecimal.valueOf(balance).multiply(monthlyRate)
        .setScale(0, RoundingMode.HALF_UP).longValue();
  }

  private static BorrowingSchedule row(Borrowing b, int no, LocalDate due,
                                       long principal, long interest, long balance) {
    return BorrowingSchedule.builder()
        .borrowingNumber(b.getBorrowingNumber())
        .installmentNo(no)
        .dueDate(due)
        .principalAmount(principal)
        .interestAmount(interest)
        .balance(balance)
        .build();
  }

  private BorrowingScheduleGenerator() {}
}
