package com.jdend.erp.loan.interest;

import com.jdend.erp.loan.policy.DebtType;
import com.jdend.erp.loan.policy.DebtorType;
import com.jdend.erp.loan.policy.LegacyAccelerationDefaultInterestPolicy;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 연체이자 계산 입력.
 *
 * <p>스펙의 {@code LoanInput} 을 그대로 옮겼다. 다만 이율은 단위 사고를 막으려고
 * {@link AnnualRate} 로 받는다({@code AnnualRate.ofPercent(18)} 또는 {@code ofFraction(0.18)}).
 */
@Getter
@Builder(toBuilder = true)
public class LoanInterestInput {

  /** 최초 대출원금. 개인채무자보호법 5,000만원 판정 기준이 이 값이다(잔여원금 아님). */
  private final long originalPrincipal;

  /** 약정 연이율 */
  private final AnnualRate contractRate;

  /** 대출기간(개월) */
  private final int termMonths;

  /** 대출 실행일 */
  private final LocalDate startDate;

  /** 첫 납기일 */
  private final LocalDate firstDueDate;

  /** 상환방식. 현재는 원리금균등만 지원한다. */
  @Builder.Default
  private final RepaymentMethod repaymentMethod = RepaymentMethod.EQUAL_PAYMENT;

  private final DebtorType debtorType;
  private final DebtType debtType;

  /**
   * 기한이익상실 <b>효력 발생일</b>. 통지 발송일과 다르다.
   * null 이면 기한이익상실이 없는 것으로 본다.
   */
  private final LocalDate eodEffectiveDate;

  /** 계산 기준일 */
  private final LocalDate asOfDate;

  /** 납입 실적. 날짜순이 아니어도 내부에서 정렬한다. */
  @Builder.Default
  private final List<LoanPaymentRecord> payments = List.of();

  /** 보호법 비적용 채권의 기한이익상실 처리 정책 */
  @Builder.Default
  private final LegacyAccelerationDefaultInterestPolicy legacyAccelerationPolicy =
      LegacyAccelerationDefaultInterestPolicy.NO_DEFAULT_INTEREST_ON_UNMATURED;

  /** 반올림·일수 정책 */
  @Builder.Default
  private final InterestCalculationOptions options = InterestCalculationOptions.defaults();

  public enum RepaymentMethod { EQUAL_PAYMENT }

  public boolean hasEod() {
    return eodEffectiveDate != null;
  }
}
