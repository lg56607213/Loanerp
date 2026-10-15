package com.jdend.erp.contract.scan.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 스캔관리 왼쪽 채권 목록의 한 줄 — 채권 정보 + 구분별 스캔 건수 */
@Getter
@Builder
public class ScanContractRowResponse {

  private String contractNumber;
  private String customerName;
  private String loanType;
  private Long loanAmount;
  private LocalDate startDate;
  private LocalDate endDate;

  private long totalCount;
  private long contractDocCount;    // 계약서
  private long collectionDocCount;  // 추심관련서류
  private long legalDocCount;       // 법적절차서류

  /** 마지막으로 스캔을 올린 시각. 없으면 null. */
  private LocalDateTime lastUploadedAt;
}
