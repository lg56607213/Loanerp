package com.jdend.erp.accounting.taxinvoice.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TaxInvoiceRequest {
  private String invoiceType;      // 매출 / 매입
  private LocalDate issueDate;
  private String approvalNo;
  private String partnerName;
  private String partnerBizNo;
  private Long supplyAmount;
  private Long taxAmount;
  /** 비우면 공급가액 + 세액으로 채운다. */
  private Long totalAmount;
  private String item;
  private String remarks;
}
