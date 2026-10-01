package com.jdend.erp.contract.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 납입금액 단수 처리 — 얼마 단위로, 어느 쪽으로 끊을지.
 *
 * <p>월할로 받는 계약은 "매월 348,700원" 처럼 백원 단위로 떨어지게 약정하는 일이 많다.
 * 계산식 그대로 348,712원을 청구하면 고객이 이체할 때마다 끝자리를 맞춰야 하고,
 * 통장 내역과 청구액이 1원씩 어긋나 대사도 번거롭다.
 *
 * <p>기본값은 원단위·반올림이다. 이는 이 기능이 생기기 전의 계산과 완전히 같다 —
 * 기존 계약의 스케줄 금액이 바뀌지 않아야 하기 때문이다.
 *
 * <p>끊는 대상은 회차별 <b>이자</b>와 <b>원금</b>이다. 마지막 회차의 원금은 끊지 않고
 * 남은 잔액을 그대로 넣는다. 그러지 않으면 단수가 쌓여 완제했는데 몇백 원이 남는다.
 */
public final class AmountRounding {

  // ── 단위 ────────────────────────────────────────────────
  public static final int UNIT_ONE     = 1;
  public static final int UNIT_TEN     = 10;
  public static final int UNIT_HUNDRED = 100;

  public static final Set<Integer> UNITS = Set.of(UNIT_ONE, UNIT_TEN, UNIT_HUNDRED);

  // ── 방식 ────────────────────────────────────────────────
  /** 절사 — 버린다. 고객에게 유리하다. */
  public static final String DOWN     = "절사";
  /** 절상 — 올린다. 회사에 유리하다. */
  public static final String UP       = "절상";
  /** 반올림 — 기본값 */
  public static final String HALF_UP  = "반올림";

  public static final Set<String> MODES = Set.of(DOWN, UP, HALF_UP);

  /** 아무것도 지정하지 않은 계약이 쓰는 값. 이 기능이 생기기 전과 같은 결과를 낸다. */
  public static final AmountRounding DEFAULT = new AmountRounding(UNIT_ONE, HALF_UP);

  private final int unit;
  private final String mode;

  private AmountRounding(int unit, String mode) {
    this.unit = unit;
    this.mode = mode;
  }

  /** 값이 없거나 모르는 값이면 기본값(원단위·반올림)으로 읽는다. */
  public static AmountRounding of(Integer unit, String mode) {
    int u = (unit != null && UNITS.contains(unit)) ? unit : UNIT_ONE;
    String m = (mode != null && MODES.contains(mode.trim())) ? mode.trim() : HALF_UP;
    return new AmountRounding(u, m);
  }

  public static boolean isValidUnit(Integer unit) { return unit == null || UNITS.contains(unit); }
  public static boolean isValidMode(String mode)  { return mode == null || MODES.contains(mode.trim()); }

  public int unit()    { return unit; }
  public String mode() { return mode; }

  /** 끊을 필요가 없는 설정인가 — 원단위 반올림이면 정수 계산과 결과가 같다. */
  public boolean isNoop() {
    return unit == UNIT_ONE && HALF_UP.equals(mode);
  }

  /**
   * 금액을 단위에 맞춰 끊는다.
   *
   * <p>음수는 들어올 일이 없지만 들어오면 0으로 본다 —
   * 음수에서 '절사'가 어느 쪽인지는 실무 해석이 갈려 추측하지 않는다.
   */
  public long apply(long amount) {
    if (amount <= 0) return 0L;
    if (unit <= 1) return amount;

    BigDecimal value = BigDecimal.valueOf(amount)
        .divide(BigDecimal.valueOf(unit), 0, roundingMode());
    return value.multiply(BigDecimal.valueOf(unit)).longValue();
  }

  /** 소수점이 있는 계산 결과를 바로 끊을 때 */
  public long apply(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) return 0L;
    if (unit <= 1) return amount.setScale(0, roundingMode()).longValue();

    return amount.divide(BigDecimal.valueOf(unit), 0, roundingMode())
        .multiply(BigDecimal.valueOf(unit))
        .longValue();
  }

  private RoundingMode roundingMode() {
    return switch (mode) {
      case DOWN -> RoundingMode.DOWN;     // 내림(버림)
      case UP   -> RoundingMode.UP;       // 올림
      default   -> RoundingMode.HALF_UP;
    };
  }

  /** 화면·메시지용 — "백원단위 절사" */
  public String describe() {
    String unitLabel = switch (unit) {
      case UNIT_HUNDRED -> "백원단위";
      case UNIT_TEN -> "십원단위";
      default -> "원단위";
    };
    return unitLabel + " " + mode;
  }
}
