package com.jdend.erp.contract.scan.support;

import java.util.List;

/**
 * 스캔 서류의 구분.
 *
 * <p>세 가지로 나눈 이유는 서류를 찾는 상황이 셋으로 갈리기 때문이다 —
 * 계약 내용을 확인할 때, 추심 경과를 소명할 때, 법원에 낼 서류를 챌 때.
 */
public final class ScanDocCategory {

  /** 계약서 — 대출계약서, 약정서, 특약, 신분증 사본 등 계약 당시 받은 서류 */
  public static final String CONTRACT = "계약서";

  /** 추심관련서류 — 독촉장, 통화녹취록, 내용증명, 채무확인서 등 */
  public static final String COLLECTION = "추심관련서류";

  /** 법적절차서류 — 지급명령, 판결문, 압류·추심명령, 송달증명 등 */
  public static final String LEGAL = "법적절차서류";

  public static final List<String> ALL = List.of(CONTRACT, COLLECTION, LEGAL);

  private ScanDocCategory() {}

  /** 화면에서 넘어온 값을 검사한다. 모르는 값은 통과시키지 않는다 — 분류가 섞이면 찾을 수 없다. */
  public static String require(String value) {
    String v = value == null ? "" : value.trim();
    if (!ALL.contains(v)) {
      throw new IllegalArgumentException("서류구분은 " + String.join(" · ", ALL) + " 중 하나여야 합니다.");
    }
    return v;
  }

  /** 조회 필터용 — 비었거나 모르는 값이면 전체(null)로 본다. */
  public static String filterOrNull(String value) {
    String v = value == null ? "" : value.trim();
    return ALL.contains(v) ? v : null;
  }
}
