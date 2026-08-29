package com.jdend.erp.loan.policy;

/** 채무자 구분 — 개인채무자보호법은 개인에게만 적용된다. */
public enum DebtorType {
  /** 개인 */
  INDIVIDUAL,
  /** 법인 */
  CORPORATE
}
