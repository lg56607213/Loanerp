package com.jdend.erp.loan.interest;

/** 기준일 현재의 회차 상태 */
public enum InstallmentStatus {

  /** 원금이 전액 납입된 회차 */
  PAID,

  /** 원래 납기일이 도래했는데 원금이 남은 회차 — 연체가산이자 대상 */
  DUE_UNPAID,

  /** 원래 납기일이 아직 안 온 회차 — 연체가산이자 대상이 아니다 */
  FUTURE,

  /**
   * 기한이익상실로 변제기가 앞당겨진 회차.
   *
   * <p>원래 납기일 기준으로는 미도래이므로, 개인채무자보호법 적용 대상이면
   * 연체가산이자는 0이고 약정이자만 붙는다. 상태 이름이 'ACCELERATED' 라고 해서
   * 연체로 취급하면 안 된다.
   */
  EOD_ACCELERATED
}
