package com.jdend.erp.payment.banktx.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UploadResultResponse {
  private String batchId;
  private int parsedRows;
  private int insertedRows;
  private int skippedDuplicates;

  /** 오류로 저장하지 못한 행 수. 예전에는 조용히 건너뛰어 0건인 이유를 알 수 없었다. */
  private int failedRows;

  /** 검증만 하고 저장하지 않았으면 true. 화면에서 먼저 확인시키는 용도. */
  private boolean dryRun;

  /** 행별 오류 내역. 화면에서 그대로 보여준다. */
  private List<RowError> errors;

  /** 중복으로 제외된 행 내역. 무엇이 왜 빠졌는지 확인할 수 있어야 한다. */
  private List<DuplicateRow> duplicates;

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor
  @Builder
  public static class RowError {
    /** 엑셀에서 보이는 행 번호(헤더가 1행). */
    private int rowNumber;
    /** 무엇이 잘못됐는지. */
    private String reason;
    /** 문제가 된 실제 값. 비어 있으면 빈 문자열. */
    private String value;
  }

  @Getter @Setter
  @NoArgsConstructor @AllArgsConstructor
  @Builder
  public static class DuplicateRow {
    private int rowNumber;
    private String txDate;
    private long deposit;
    private long withdrawal;
    private long balance;
    private String summary;
    /** "이미 등록된 내역" 또는 "이 파일 안에서 중복" */
    private String reason;
  }
}
