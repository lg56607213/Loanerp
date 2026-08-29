package com.jdend.erp.loan.policy;

/**
 * 개인채무자보호법(개인금융채권의 관리 및 개인금융채무자의 보호에 관한 법률) 적용 판정.
 *
 * <p>세 가지를 모두 만족해야 적용 대상이다.
 * <ul>
 *   <li>최초 대출원금 5,000만원 <b>미만</b></li>
 *   <li>채무자가 개인</li>
 *   <li>채권이 개인금융채권</li>
 * </ul>
 *
 * <p>적용되면 기한이익상실이 나더라도 <b>원래 납기일이 도래하지 않은 원금에는
 * 연체가산이자를 붙일 수 없다.</b> 이 판정 하나가 연체이자 계산 전체의 갈림길이라
 * 별도 클래스로 떼어 두었다.
 *
 * <p>금액 기준은 '최초 대출원금'이다. 잔여원금이 5,000만원 아래로 내려왔다고
 * 나중에 적용 대상이 되는 것이 아니다.
 */
public final class PersonalDebtorProtection {

  /** 보호 대상 상한 — 최초 원금이 이 금액 미만이어야 한다(경계값 5,000만원은 미적용). */
  public static final long PRINCIPAL_THRESHOLD = 50_000_000L;

  public static boolean applies(long originalPrincipal, DebtorType debtorType, DebtType debtType) {
    return originalPrincipal < PRINCIPAL_THRESHOLD
        && debtorType == DebtorType.INDIVIDUAL
        && debtType == DebtType.PERSONAL_FINANCIAL_CLAIM;
  }

  /** 판정 근거를 사람이 읽을 수 있게 남긴다(감사 로그용). */
  public static String describe(long originalPrincipal, DebtorType debtorType, DebtType debtType) {
    boolean amountOk = originalPrincipal < PRINCIPAL_THRESHOLD;
    boolean debtorOk = debtorType == DebtorType.INDIVIDUAL;
    boolean typeOk = debtType == DebtType.PERSONAL_FINANCIAL_CLAIM;
    return String.format(
        "개인채무자보호법 적용 판정 = %s (최초원금 %,d원 < 5,000만원: %s / 채무자 개인: %s / 개인금융채권: %s)",
        (amountOk && debtorOk && typeOk) ? "적용" : "미적용",
        originalPrincipal, yn(amountOk), yn(debtorOk), yn(typeOk));
  }

  private static String yn(boolean b) { return b ? "예" : "아니오"; }

  private PersonalDebtorProtection() {}
}
