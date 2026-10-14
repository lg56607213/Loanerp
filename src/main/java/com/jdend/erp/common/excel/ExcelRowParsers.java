package com.jdend.erp.common.excel;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** 엑셀 한 행(헤더명 → 문자열값 Map)에서 타입별 값을 꺼내는 공통 헬퍼. */
public final class ExcelRowParsers {

  private ExcelRowParsers() {}

  public static String str(Map<String, String> row, String key) {
    String v = row.get(key);
    return (v == null || v.isBlank()) ? null : v.trim();
  }

  public static Long longVal(Map<String, String> row, String key) {
    String v = str(row, key);
    if (v == null) return null;
    try {
      return Long.parseLong(v.replace(",", "").trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("'" + key + "' 값이 올바른 숫자가 아닙니다: " + v);
    }
  }

  public static Integer intVal(Map<String, String> row, String key) {
    Long v = longVal(row, key);
    return v == null ? null : v.intValue();
  }

  public static LocalDate dateVal(Map<String, String> row, String key) {
    String v = str(row, key);
    if (v == null) return null;
    try {
      return parseDate(v);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "'" + key + "' 값이 날짜가 아닙니다: " + v + " (8자리 숫자로 적으세요. 예: 20260630)");
    }
  }

  /**
   * 엑셀 셀의 날짜 문자열 → LocalDate.
   *
   * <p>표준은 <b>8자리 숫자</b>(20260630)다. 엑셀에서 "2026-06-30" 처럼 적으면 셀이 날짜
   * 서식으로 바뀌어, 읽을 때 서식에 따라 "6/30/26" 이나 일련번호로 넘어온다. 숫자 8자리로
   * 적으면 그냥 숫자라 그런 변환이 끼어들지 않는다.
   *
   * <p>다만 이미 만들어 둔 파일이 있을 수 있어 아래 표기도 그대로 받는다.
   * <ul>
   *   <li>20260630 — 권장</li>
   *   <li>2026-06-30 / 2026.06.30 / 2026/06/30</li>
   *   <li>2026년 6월 30일</li>
   *   <li>엑셀 날짜 일련번호 (46203)</li>
   *   <li>6/30/2026, 6/30/26 — 엑셀이 날짜 서식 셀을 이렇게 내려준다</li>
   *   <li>20,260,630 — 천단위 서식이 붙은 8자리 숫자</li>
   * </ul>
   */
  public static LocalDate parseDate(String s) {
    String raw = (s == null) ? "" : s.trim();
    if (raw.isBlank()) throw new IllegalArgumentException("빈 값");

    // 엑셀 날짜 일련번호가 서식 없이 그대로 넘어온 경우.
    // 엑셀은 1900-02-29 를 존재한 날처럼 세므로 기준일이 1899-12-30 이다.
    if (raw.matches("^\\d{5}(\\.0+)?$")) {
      long serial = (long) Double.parseDouble(raw);
      return LocalDate.of(1899, 12, 30).plusDays(serial);
    }

    // 엑셀이 20260630 을 천단위 서식으로 "20,260,630" 으로 내려주는 경우가 있어 쉼표를 먼저 턴다.
    String v = raw.replace(",", "").replace(".", "-").replace("/", "-").replace(" ", "");
    v = v.replace("년", "-").replace("월", "-").replace("일", "");
    v = v.replaceAll("-+$", "");

    if (v.matches("^\\d{8}$")) {
      v = v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8);
    }

    String[] p = v.split("-");
    if (p.length == 3) {
      // 표기가 여러 가지라 후보를 만들어 보고 실제로 존재하는 날짜를 고른다.
      // 엑셀 기본 날짜 서식(numFmtId 14)은 m/d/yy 라 "12/29/23" 처럼 두 자리 연도로 온다.
      Integer a = toInt(p[0]), b = toInt(p[1]), c = toInt(p[2]);
      if (a != null && b != null && c != null) {
        if (p[0].length() == 4) {
          LocalDate d = tryDate(a, b, c);            // yyyy-M-d
          if (d != null) return d;
        }
        if (p[2].length() == 4) {
          LocalDate d = tryDate(c, a, b);            // M-d-yyyy
          if (d != null) return d;
        }
        if (p[2].length() == 2) {
          LocalDate d = tryDate(2000 + c, a, b);     // M-d-yy (엑셀 기본)
          if (d != null) return d;
        }
        if (p[0].length() == 2) {
          LocalDate d = tryDate(2000 + a, b, c);     // yy-M-d
          if (d != null) return d;
        }
      }
    }
    return LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
  }

  private static Integer toInt(String s) {
    try { return Integer.parseInt(s); } catch (Exception e) { return null; }
  }

  /** 존재하지 않는 날짜(13월 등)면 null 을 돌려 다음 후보로 넘어가게 한다. */
  private static LocalDate tryDate(int year, int month, int day) {
    try { return LocalDate.of(year, month, day); } catch (Exception e) { return null; }
  }
}
