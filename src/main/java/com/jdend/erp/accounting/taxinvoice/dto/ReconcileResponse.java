package com.jdend.erp.accounting.taxinvoice.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

/** 세금계산서 ↔ 통장 ↔ 전표 3자 대조 결과 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ReconcileResponse {

  private LocalDate startDate;
  private LocalDate endDate;
  private int dayWindow;
  private boolean matchByName;

  /** 유형별 건수·금액 */
  private List<Summary> summary;
  private List<Row> rows;

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor @Builder
  public static class Summary {
    private String type;
    private String note;
    private int count;
    private long amount;
  }

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor @Builder
  public static class Row {
    /** 미수금 / 미지급금 / 매출 세금계산서 없음 / 매입 세금계산서 없음 / 전표 없음 */
    private String type;
    private LocalDate date;
    private String partnerName;
    private long amount;

    /** 세 가지가 각각 있는지 */
    private boolean hasInvoice;
    private boolean hasBankTx;
    private boolean hasVoucher;

    private Long invoiceId;
    private String invoiceType;
    private String bankSummary;
    private LocalDate bankDate;
    private String voucherNo;

    private String note;
  }
}
