package com.jdend.erp.loan.policy;

/**
 * 개인채무자보호법 <b>비적용</b> 채권에서, 기한이익상실 이후 미도래 원금을 어떻게 볼지 정하는 정책.
 *
 * <p>보호법이 적용되지 않는다고 해서 자동으로 잔액 전체에 연체이율을 붙이면 안 된다.
 * 그건 법이 아니라 회사가 고르는 정책이고, 약관·계약서에 근거가 있어야 한다.
 * 그래서 기본값을 '미도래 원금에는 가산이자를 붙이지 않음'으로 두고,
 * 붙이려면 회사가 명시적으로 선택하게 한다.
 */
public enum LegacyAccelerationDefaultInterestPolicy {

  /**
   * 기본값. 기한이익상실이 나도 원래 납기일이 도래하지 않은 원금에는
   * 연체가산이자를 붙이지 않는다(약정이자만 붙는다).
   */
  NO_DEFAULT_INTEREST_ON_UNMATURED,

  /**
   * 기한이익상실일 이후 잔여원금 전액에 연체이율을 적용한다.
   * 계약서·약관에 근거가 있을 때만 쓴다.
   */
  DEFAULT_INTEREST_ON_ENTIRE_BALANCE
}
