package com.jdend.erp.loan.interest;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 회차별 계산 결과.
 *
 * <p><b>정상이자와 연체가산이자를 어떻게 나눴는가.</b>
 * 연체이율(예: 20%)은 '정상이자 + 가산이자'를 합친 최종 이율이다.
 * 그래서 연체 회차의 이자를 이렇게 쪼갠다.
 *
 * <pre>
 *   ordinaryInterestAccrued = 연체원금 × 약정이율(18%)        × 연체일수 / 365
 *   defaultInterestAccrued  = 연체원금 × (연체이율 − 약정이율) × 연체일수 / 365
 *                           = 연체원금 × 2%p                  × 연체일수 / 365
 *   ----------------------------------------------------------------
 *   두 값의 합 = 연체원금 × 연체이율(20%) × 연체일수 / 365
 * </pre>
 *
 * 이렇게 해야 18% + 20% = 38% 로 중복 합산되지 않는다.
 * {@code defaultInterestAccrued} 가 곧 법에서 말하는 '연체가산이자'이고,
 * 개인채무자보호법이 제한하는 대상이 정확히 이 금액이다.
 */
@Getter
@Builder
public class InstallmentCharge {

  private final int installmentNo;

  /** 원래 계약상 납기일. 기한이익상실이 나도 바뀌지 않는다. */
  private final LocalDate dueDate;

  private final long scheduledPayment;
  private final long scheduledPrincipal;
  private final long scheduledInterest;

  private final long paidPrincipal;
  private final long paidInterest;
  private final long paidDefaultInterest;

  /** 미납 원금 = max(0, scheduledPrincipal − paidPrincipal) */
  private final long overduePrincipal;

  /** 기준일까지 발생한 약정이자 */
  private final long ordinaryInterestAccrued;

  /** 기준일까지 발생한 연체가산이자 (약정이율 초과분) */
  private final long defaultInterestAccrued;

  /** 연체일수. 납기일 다음날부터 기산하며 납기일 당일 납부는 0일이다. */
  private final long overdueDays;

  private final InstallmentStatus status;

  /** 이 회차 원금에 실제로 적용된 최종 연이율 (연체면 연체이율, 아니면 약정이율) */
  private final AnnualRate applicableAnnualRate;

  /** 이자 기산 시작일 (연체 회차는 납기일 다음날) */
  private final LocalDate accrualFrom;

  /** 이자 기산 종료일 (납부일 또는 기준일) */
  private final LocalDate accrualTo;

  /** 이자가 붙은 기준 원금 */
  private final long interestBasePrincipal;

  /** 이 회차에서 기준일 현재 청구 가능한 금액 */
  public long payableTotal() {
    return overduePrincipal + ordinaryInterestAccrued + defaultInterestAccrued;
  }

  /** 발생 이자 합계 (약정 + 가산) */
  public long totalInterestAccrued() {
    return ordinaryInterestAccrued + defaultInterestAccrued;
  }
}
