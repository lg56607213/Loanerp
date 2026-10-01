package com.jdend.erp.borrowing.support;

import java.util.Set;

/** 차입금에서 쓰는 코드값. 화면과 서버가 같은 문자열을 쓰도록 한곳에 모은다. */
public final class BorrowingConstants {

  // ── 상태 ────────────────────────────────────────────────
  /** 아직 갚을 원금이 남은 상태 */
  public static final String NORMAL = "정상";
  /** 원금을 다 갚은 상태 */
  public static final String CLOSED = "완제";

  // ── 상환방식 ────────────────────────────────────────────
  /** 만기에 원금을 한 번에 갚는다. 이자는 기간마다 낸다. */
  public static final String BULLET = "만기일시";
  /** 매회 같은 원금을 갚는다. 이자는 줄어든 잔액에 붙어 점점 작아진다. */
  public static final String EQUAL_PRINCIPAL = "원금균등";
  /** 매회 원금+이자 합계가 같다. */
  public static final String EQUAL_INSTALLMENT = "원리금균등";
  /** 약정 없이 형편에 따라 갚는다. 스케줄을 만들지 않는다. */
  public static final String ON_DEMAND = "수시상환";

  public static final Set<String> REPAYMENT_METHODS =
      Set.of(BULLET, EQUAL_PRINCIPAL, EQUAL_INSTALLMENT, ON_DEMAND);

  /** 스케줄을 만들 수 있는 상환방식인가 — 수시상환은 약정 회차가 없다. */
  public static boolean hasSchedule(String method) {
    return BULLET.equals(method)
        || EQUAL_PRINCIPAL.equals(method)
        || EQUAL_INSTALLMENT.equals(method);
  }

  // ── 차입구분 ────────────────────────────────────────────
  public static final Set<String> BORROWING_TYPES =
      Set.of("금융기관", "개인", "법인", "관계사");

  private BorrowingConstants() {}
}
