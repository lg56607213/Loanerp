package com.jdend.erp.accounting.statements.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomeStatementResponse {
  private StatementNodeResponse revenue;
  private StatementNodeResponse expense;

  private Long totalRevenue;
  private Long totalExpense;

  /**
   * 영업손익 구분 — 대부업은 대출채권 이자수익(영업수익, 4001)과
   * 예금이자 등 영업외수익(4002)이 모두 "이자수익" 성격이라 반드시 구분해서 집계한다.
   *   영업이익   = 영업수익 - 영업비용(매출원가 + 판매비와관리비)
   *   당기순이익 = 영업이익 + 영업외수익 - 영업외비용 - 법인세
   */
  private Long operatingRevenue;
  private Long operatingExpense;
  private Long operatingIncome;
  private Long nonOperatingRevenue;
  private Long nonOperatingExpense;
  private Long incomeTax;

  private Long netIncome;
}
