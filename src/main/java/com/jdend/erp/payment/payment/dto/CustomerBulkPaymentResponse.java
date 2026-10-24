package com.jdend.erp.payment.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** 일괄수납 배분 결과 — 어느 채권에 얼마가 들어가는지. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBulkPaymentResponse {

  private String customerNumber;
  private String customerName;
  private LocalDate paymentDate;

  /** 받은 금액 */
  private Long amount;

  /** 실제로 채권에 들어간 금액 */
  private Long allocated;

  /** 갚을 것을 다 메우고 남은 금액 — 선수금으로 남는다 */
  private Long excess;

  /** 저장했는지(미리보기면 false) */
  private boolean saved;

  private List<Line> lines;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Line {
    private String contractNumber;
    private LocalDate executeDate;
    private Long loanAmount;
    private Long remainingPrincipal;

    /** 이 채권이 그날까지 받아야 할 몫 */
    private Long dueCost;
    private Long dueOverdueInterest;
    private Long dueInterest;
    private Long duePrincipal;

    /** 이번에 이 채권에 넣는 금액 */
    private Long paidCost;
    private Long paidOverdueInterest;
    private Long paidInterest;
    private Long paidPrincipal;
    private Long paidTotal;

    /** 저장했을 때 만들어진 수납 id */
    private Long paymentId;
  }
}
