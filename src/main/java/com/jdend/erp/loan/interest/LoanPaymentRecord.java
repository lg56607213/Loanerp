package com.jdend.erp.loan.interest;

import java.time.LocalDate;

/**
 * 납입 1건.
 *
 * <p>배분 결과(principalAmount 등)가 이미 있으면 그대로 쓰고,
 * 없으면 {@code amount} 를 이 모듈이 회차에 배분한다.
 *
 * <p>{@code installmentNo} 를 주면 그 회차에 직접 꽂는다. 일반적인 변제충당은
 * 오래된 회차부터 채우지만, 이미 회차별로 소구된 실적을 그대로 옮길 때가 있어
 * 지정 수단을 열어 둔다.
 *
 * @param paymentDate        납입일
 * @param amount             납입 총액
 * @param principalAmount    배분된 원금 (모르면 null)
 * @param interestAmount     배분된 약정이자 (모르면 null)
 * @param lateInterestAmount 배분된 연체가산이자 (모르면 null)
 * @param installmentNo      귀속 회차 (모르면 null → 오래된 회차부터 배분)
 */
public record LoanPaymentRecord(
    LocalDate paymentDate,
    long amount,
    Long principalAmount,
    Long interestAmount,
    Long lateInterestAmount,
    Integer installmentNo
) {

  /** 총액만 아는 납입. 오래된 회차부터 배분된다. */
  public static LoanPaymentRecord of(LocalDate paymentDate, long amount) {
    return new LoanPaymentRecord(paymentDate, amount, null, null, null, null);
  }

  /** 배분 결과를 아는 납입. 오래된 회차부터 채운다. */
  public static LoanPaymentRecord allocated(LocalDate paymentDate, long principal,
                                            long interest, long lateInterest) {
    return new LoanPaymentRecord(paymentDate, principal + interest + lateInterest,
        principal, interest, lateInterest, null);
  }

  /** 특정 회차에 귀속되는 납입 */
  public static LoanPaymentRecord forInstallment(LocalDate paymentDate, int installmentNo,
                                                 long principal, long interest, long lateInterest) {
    return new LoanPaymentRecord(paymentDate, principal + interest + lateInterest,
        principal, interest, lateInterest, installmentNo);
  }

  /** 배분 결과가 이미 들어 있는가 */
  public boolean isPreAllocated() {
    return principalAmount != null || interestAmount != null || lateInterestAmount != null;
  }

  public long principalOrZero()    { return principalAmount    == null ? 0L : principalAmount; }
  public long interestOrZero()     { return interestAmount     == null ? 0L : interestAmount; }
  public long lateInterestOrZero() { return lateInterestAmount == null ? 0L : lateInterestAmount; }
}
