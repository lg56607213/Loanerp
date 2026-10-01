package com.jdend.erp.loan.policy;

/**
 * 도래한 회차를 다 메우고도 돈이 남았을 때 어떻게 할지.
 *
 * <p>월 납입액이 정해진 채권에서 고객이 조금 더 보내는 일은 흔하다.
 * 그 돈을 바로 미래 회차 원금에 넣어 버리면 원금이 줄어 다음 달 이자가 달라지고,
 * 고객이 생각한 '다음 달 치를 미리 낸 것'과 장부가 어긋난다.
 *
 * <p>반대로 정말 원금을 빨리 줄이려고 더 보낸 것이라면 선수금으로 묶어 두는 쪽이 틀렸다.
 * 어느 쪽인지는 돈을 받은 사람만 알 수 있으므로 수납할 때 고르게 한다.
 */
public enum PaymentSurplusPolicy {

  /**
   * 기본값. 남은 돈을 아직 도래하지 않은 회차의 원금에 넣는다.
   * 조기 원금상환이 되어 잔여원금이 줄고 이후 이자도 줄어든다.
   */
  REDUCE_PRINCIPAL("원금충당"),

  /**
   * 남은 돈을 선수금으로 둔다. 잔여원금은 그대로다.
   * 다음 회차가 도래하면 선수금으로 수납해 메운다.
   */
  HOLD_AS_PREPAID("선수금");

  private final String label;

  PaymentSurplusPolicy(String label) { this.label = label; }

  /** 화면·DB 에 쓰는 한글 표기 */
  public String label() { return label; }

  /** 한글 표기로 찾는다. 값이 없거나 모르는 값이면 기본값(원금충당). */
  public static PaymentSurplusPolicy of(String label) {
    if (label != null) {
      for (PaymentSurplusPolicy p : values()) {
        if (p.label.equals(label.trim())) return p;
      }
    }
    return REDUCE_PRINCIPAL;
  }
}
