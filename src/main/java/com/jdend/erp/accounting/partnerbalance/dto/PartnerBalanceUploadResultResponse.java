package com.jdend.erp.accounting.partnerbalance.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerBalanceUploadResultResponse {
  private int parsedRows;
  private int insertedRows;
  private int skippedDuplicates;
  private int failedRows;
  private boolean dryRun;
  private List<RowError> errors;

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor @Builder
  public static class RowError {
    private int rowNumber;
    private String reason;
    private String value;
  }
}
