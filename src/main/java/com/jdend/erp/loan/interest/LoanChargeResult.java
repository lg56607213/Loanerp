package com.jdend.erp.loan.interest;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.time.LocalDate;
import java.util.List;

/**
 * 기준일 현재의 계산 결과.
 *
 * <p>원금 / 정상 약정이자 / 연체가산이자 / 총 청구액을 분리해 돌려준다.
 * 분쟁이 생겼을 때 어느 회차에 어느 이율이 며칠 붙었는지 {@link #getAuditLogs()} 로 설명할 수 있어야 한다.
 */
@Getter
@Builder
public class LoanChargeResult {

  private final AnnualRate contractRate;

  /** 상한 적용 전 산출값 (약정이율 + 3%p) */
  private final AnnualRate calculatedDefaultRate;

  /** 실제 적용 연체이율 = min(약정이율 + 3%p, 20%) */
  private final AnnualRate defaultRate;

  /** 개인채무자보호법 적용 대상인가 */
  private final boolean personalDebtorProtectionApplies;

  private final LocalDate eodEffectiveDate;
  private final LocalDate asOfDate;

  /** 기준일 현재 미납 원금 합계 */
  private final long remainingPrincipal;

  /** 스케줄상 약정이자 총액 (계약 조건) */
  private final long totalScheduledInterest;

  /** 기준일까지 발생한 약정이자 합계 */
  private final long totalOrdinaryInterestAccrued;

  /** 기준일까지 발생한 연체가산이자 합계 */
  private final long totalDefaultInterestAccrued;

  /** 총 청구액 = 미납원금 + 발생 약정이자 + 발생 연체가산이자 */
  private final long totalPayable;

  @Singular("installment")
  private final List<InstallmentCharge> installments;

  @Singular("auditLog")
  private final List<String> auditLogs;
}
