package com.jdend.erp.loan.policy;

/** 채권 성격 — 개인채무자보호법의 보호 대상은 '개인금융채권'이다. */
public enum DebtType {
  /** 개인금융채권 */
  PERSONAL_FINANCIAL_CLAIM,
  /** 그 밖의 채권 */
  OTHER
}
