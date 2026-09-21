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

  /** 행별 오류 내역. 화면에서 그대로 보여준다. */
  private List<RowError> errors;

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
}
