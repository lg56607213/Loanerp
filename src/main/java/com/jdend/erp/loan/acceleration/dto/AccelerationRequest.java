package com.jdend.erp.loan.acceleration.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AccelerationRequest {
  private String contractNumber;
  private LocalDate eodDate;
  private LocalDate noticeDate;
  private String reason;
  private String memo;
  /** 전표 자동 생성 여부 */
  private Boolean createVoucher;
}
