package com.jdend.erp.contract.scan.dto;

import com.jdend.erp.contract.scan.entity.ContractScan;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 스캔 서류 한 장 */
@Getter
@Builder
public class ContractScanResponse {

  private Long id;
  private String contractNumber;
  private String docCategory;
  private String docTitle;
  private LocalDate docDate;
  private String originalName;
  private String contentType;
  private Long fileSize;
  private String uploadedBy;
  private String memo;
  private LocalDateTime createdAt;

  /** 화면에서 바로 열 수 있는지 — PDF·이미지는 미리보기, 그 외는 내려받기만 */
  private boolean previewable;

  public static ContractScanResponse of(ContractScan s) {
    String type = s.getContentType() == null ? "" : s.getContentType();
    return ContractScanResponse.builder()
        .id(s.getId())
        .contractNumber(s.getContractNumber())
        .docCategory(s.getDocCategory())
        .docTitle(s.getDocTitle())
        .docDate(s.getDocDate())
        .originalName(s.getOriginalName())
        .contentType(s.getContentType())
        .fileSize(s.getFileSize())
        .uploadedBy(s.getUploadedBy())
        .memo(s.getMemo())
        .createdAt(s.getCreatedAt())
        .previewable(type.equals("application/pdf") || type.startsWith("image/"))
        .build();
  }
}
