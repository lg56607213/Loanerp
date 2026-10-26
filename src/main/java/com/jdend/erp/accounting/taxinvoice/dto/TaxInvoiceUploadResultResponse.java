package com.jdend.erp.accounting.taxinvoice.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TaxInvoiceUploadResultResponse {
  private String batchId;
  private int parsedRows;
  private int insertedRows;
  private int skippedDuplicates;
  private int failedRows;
  private boolean dryRun;
  private List<RowError> errors;
  private List<String> duplicates;

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor @Builder
  public static class RowError {
    private int rowNumber;
    private String reason;
    private String value;
  }
}
