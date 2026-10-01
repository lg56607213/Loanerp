package com.jdend.erp.borrowing.dto;

import lombok.*;

import java.time.LocalDate;

/** 이자 자동계산 결과 — 화면 금액칸에 미리 채워 넣는 값 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class InterestPreviewResponse {

  public String borrowingNumber;
  public LocalDate from;        // 기산일 (제외)
  public LocalDate to;          // 지급일 (포함)
  public Integer days;
  public Long balance;          // 이자가 붙는 미상환 원금
  public Long interestAmount;   // 계산된 이자
  public String basis;          // 계산 근거 한 줄 (화면에 그대로 보여준다)
}
