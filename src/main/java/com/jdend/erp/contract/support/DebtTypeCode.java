package com.jdend.erp.contract.support;

import com.jdend.erp.loan.policy.DebtType;
import com.jdend.erp.loan.policy.PersonalDebtorProtection;

/**
 * 계약에 저장하는 채권 성격 코드.
 *
 * <p>DB·화면에는 한글로 두고({@code 개인금융채권} / {@code 기타}),
 * 계산 모듈에는 {@link DebtType} 으로 넘긴다.
 */
public final class DebtTypeCode {

  public static final String PERSONAL_FINANCIAL_CLAIM = "개인금융채권";
  public static final String OTHER = "기타";

  /**
   * 미입력 시 기본값.
   *
   * <p>개인 채무자이면서 최초원금이 5,000만원 미만이면 개인금융채권으로 본다.
   * 대부업 여신은 대부분 여기에 해당하고, 잘못 잡았을 때 채무자에게 불리해지는 쪽
   * (기타로 두어 가산이자를 붙이는 쪽)보다 안전하다.
   * 실제와 다르면 대출등록 화면에서 바꾼다.
   */
  public static String defaultFor(String customerType, Long loanAmount) {
    boolean individual = customerType == null || "개인".equals(customerType);
    long principal = loanAmount == null ? 0L : loanAmount;
    return (individual && principal < PersonalDebtorProtection.PRINCIPAL_THRESHOLD)
        ? PERSONAL_FINANCIAL_CLAIM : OTHER;
  }

  /** 저장값을 계산 모듈의 enum 으로 */
  public static DebtType toDebtType(String code) {
    return PERSONAL_FINANCIAL_CLAIM.equals(code) ? DebtType.PERSONAL_FINANCIAL_CLAIM : DebtType.OTHER;
  }

  /** 입력값 정규화. 알 수 없는 값은 '기타'로 떨어뜨린다. */
  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String v = raw.trim();
    return PERSONAL_FINANCIAL_CLAIM.equals(v) ? PERSONAL_FINANCIAL_CLAIM : OTHER;
  }

  private DebtTypeCode() {}
}
