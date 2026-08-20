package com.jdend.erp.loan.writeoff.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class WriteOffRequest {
  private String contractNumber;
  private LocalDate writeOffDate;
  private String reason;
  /** 대손충당금 상계액 — 미입력 시 0, 나머지는 대손상각비로 계상 */
  private Long allowanceUsed;
  private String memo;
  private Boolean createVoucher;
}
