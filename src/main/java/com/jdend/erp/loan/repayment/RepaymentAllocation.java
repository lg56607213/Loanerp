package com.jdend.erp.loan.repayment;

import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/** 변제충당 결과 — 입금액을 항목별로 얼마씩 나눴는지와 회차별 내역을 담는다. */
@Getter
@ToString
public class RepaymentAllocation {

  private long cost;            // 법적비용
  private long overdueInterest; // 지연배상금
  private long interest;        // 약정이자
  private long principal;       // 원금
  private long excess;          // 충당하고 남은 금액 → 선수금

  private final List<Line> lines = new ArrayList<>();

  /** 회차별 충당 내역 */
  @Getter
  @ToString
  public static class Line {
    private final Long scheduleId;
    private final Integer installmentNo;
    private long overdueInterest;
    private long interest;
    private long principal;

    Line(Long scheduleId, Integer installmentNo) {
      this.scheduleId = scheduleId;
      this.installmentNo = installmentNo;
    }

    void addOverdueInterest(long v) { overdueInterest += v; }
    void addInterest(long v) { interest += v; }
    void addPrincipal(long v) { principal += v; }

    public long total() { return overdueInterest + interest + principal; }
  }

  Line lineFor(Long scheduleId, Integer installmentNo) {
    for (Line l : lines) {
      if (l.installmentNo != null && l.installmentNo.equals(installmentNo)) return l;
    }
    Line l = new Line(scheduleId, installmentNo);
    lines.add(l);
    return l;
  }

  void addCost(long v) { cost += v; }
  void addOverdueInterest(long v) { overdueInterest += v; }
  void addInterest(long v) { interest += v; }
  void addPrincipal(long v) { principal += v; }
  void setExcess(long v) { excess = v; }

  public long allocatedTotal() {
    return cost + overdueInterest + interest + principal;
  }
}
