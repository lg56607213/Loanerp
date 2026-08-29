package com.jdend.erp.loan.support;

import com.jdend.erp.contract.entity.Contract;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 대출채권을 단기대여금으로 잡을지 장기대여금으로 잡을지 정한다.
 *
 * 규칙 (사장 결정): <b>대출기간이 1년 미만이면 단기대여금, 1년 이상이면 장기대여금.</b>
 *
 * <p>기간은 실행일(없으면 시작일)부터 종료일까지의 <b>일수</b>로 잰다.
 * 개월수로 재면 2026-01-10 ~ 2027-01-10 처럼 딱 1년인 계약이 월 경계 처리에 따라
 * 11개월로 떨어질 수 있어 판정이 흔들린다. 365일 기준이 실무 표현('1년')과도 맞는다.
 *
 * <p>날짜가 비어 있으면 회차수로 판정한다(월납 기준 12회 = 1년).
 * 둘 다 없으면 장기로 본다 — 대부업 여신은 장기가 더 흔하고,
 * 잘못 잡았을 때 회수 기간을 과소평가하는 쪽(단기)보다 안전하다.
 *
 * <p>설계서 §6 '실측' 항목에서 지적된 대여금 계정 3종 혼용(단기/장기/대여금)을
 * 이 한 곳의 규칙으로 정리한다. 실행·회수·상각이 모두 같은 계정을 쓰게 해야
 * 계정별 잔액이 어긋나지 않는다.
 */
public final class LoanReceivableAccount {

  /** 1년 = 365일. 윤년도 365일 고정 (일할 정산과 같은 기준) */
  private static final long ONE_YEAR_DAYS = 365L;
  /** 월납 기준 1년 */
  private static final int ONE_YEAR_INSTALLMENTS = 12;

  public static final String SHORT_CODE = "100301";
  public static final String SHORT_NAME = "단기대여금";
  public static final String LONG_CODE  = "100302";
  public static final String LONG_NAME  = "장기대여금";

  /** 단기대여금 대손충당금 */
  public static final String SHORT_ALLOWANCE_CODE = "10030101";
  public static final String SHORT_ALLOWANCE_NAME = "단기대여금 대손충당금";
  /** 장기대여금 대손충당금 */
  public static final String LONG_ALLOWANCE_CODE  = "10030201";
  public static final String LONG_ALLOWANCE_NAME  = "장기대여금 대손충당금";

  /** 대출기간이 1년 미만인가 */
  public static boolean isShortTerm(Contract c) {
    if (c == null) return false;

    LocalDate from = c.getExecuteDate() != null ? c.getExecuteDate() : c.getStartDate();
    LocalDate to = c.getEndDate();
    if (from != null && to != null && !to.isBefore(from)) {
      return ChronoUnit.DAYS.between(from, to) < ONE_YEAR_DAYS;
    }

    Integer months = c.getInstallmentCount();
    if (months != null && months > 0) {
      return months < ONE_YEAR_INSTALLMENTS;
    }

    return false;   // 판단 근거가 없으면 장기
  }

  /** 대출채권 계정코드 */
  public static String codeOf(Contract c) {
    return isShortTerm(c) ? SHORT_CODE : LONG_CODE;
  }

  /** 대출채권 계정명 */
  public static String nameOf(Contract c) {
    return isShortTerm(c) ? SHORT_NAME : LONG_NAME;
  }

  /** 대손충당금 계정코드 — 대여금과 짝이 맞아야 한다 */
  public static String allowanceCodeOf(Contract c) {
    return isShortTerm(c) ? SHORT_ALLOWANCE_CODE : LONG_ALLOWANCE_CODE;
  }

  /** 대손충당금 계정명 */
  public static String allowanceNameOf(Contract c) {
    return isShortTerm(c) ? SHORT_ALLOWANCE_NAME : LONG_ALLOWANCE_NAME;
  }

  private LoanReceivableAccount() {}
}
