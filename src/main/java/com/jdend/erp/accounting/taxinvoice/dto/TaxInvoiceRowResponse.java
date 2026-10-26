package com.jdend.erp.accounting.taxinvoice.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TaxInvoiceRowResponse {
  private Long id;
  private String invoiceType;
  private LocalDate issueDate;
  private String approvalNo;
  private String partnerName;
  private String partnerBizNo;
  private Long supplyAmount;
  private Long taxAmount;
  private Long totalAmount;
  private String item;
  private String remarks;
}
