package com.jdend.erp.contract.entity;

import java.util.Set;

/**
 * 채권 상태 5종.
 *
 * 저장 상태와 파생 상태를 구분한다.
 *  - 해지/상각/종료: 기한이익상실·대손상각·완제 이벤트 시점에 contracts.status에 저장한다.
 *  - 정상/연체: 저장하지 않고 미납 스케줄로 조회 시점에 판정한다(연체는 납입예정일 D+1부터).
 */
public final class ContractStatus {

  /** 미납 회차 없음 — 파생 상태 */
  public static final String NORMAL = "정상";
  /** 납입예정일 경과 미납 존재 — 파생 상태 */
  public static final String OVERDUE = "연체";
  /** 기한이익상실 등록됨 → 잔여원금 전액 즉시 청구 */
  public static final String ACCELERATED = "해지";
  /** 대손상각 등록됨 */
  public static final String WRITTEN_OFF = "상각";
  /** 완제 또는 만기 정상종료 */
  public static final String CLOSED = "종료";

  /** 이벤트로 확정되어 DB에 저장되는 상태 */
  public static final Set<String> STORED = Set.of(ACCELERATED, WRITTEN_OFF, CLOSED);

  /** 연체 파생 판정 대상에서 제외되는 상태 */
  public static final Set<String> EXCLUDED_FROM_OVERDUE = Set.of(WRITTEN_OFF, CLOSED);

  public static final Set<String> ALL = Set.of(NORMAL, OVERDUE, ACCELERATED, WRITTEN_OFF, CLOSED);

  /** 대손상각은 연체 또는 해지 상태에서만 가능하다(정상 채권 상각 불가). */
  public static boolean canWriteOff(String current) {
    return OVERDUE.equals(current) || ACCELERATED.equals(current);
  }

  private ContractStatus() {}
}
