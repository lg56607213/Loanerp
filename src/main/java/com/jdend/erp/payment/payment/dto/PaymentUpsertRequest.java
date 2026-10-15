package com.jdend.erp.payment.payment.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentUpsertRequest {
  private String contractNumber;
  private LocalDate paymentDate;
  private Long paymentAmount;
  private String paymentMethod;
  private String companyAccount;
  private String memo;

  /** 충당기준일. 비우면 수납일을 쓴다. (납입일 전에 미리 받은 돈을 납입일자로 처리할 때) */
  private LocalDate applyDate;

  /** 도래 회차를 메우고 남은 돈 — "원금충당" / "선수금". 비우면 원금충당. */
  private String surplusPolicy;

  /** 수납 재원 — "보통예금" / "선수금". 비우면 보통예금. */
  private String paymentSource;

  /**
   * true(기본/null) = 전표 생성 + 수납상태 변경
   * false           = 수납상태만 변경, 전표 미생성 (기존 데이터 컨버전용)
   */
  private Boolean createVoucher;

  /**
   * 지정 충당 — 원금·이자를 직접 적어 넣을 때만 쓴다(상환내역 업로드).
   * 비워 두면 평소처럼 변제충당 순서대로 계산한다.
   */
  private Long fixedPrincipal;
  private Long fixedInterest;
  /** 파일에 적힌 상환 후 잔액. 대조용. */
  private Long fixedBalance;
}