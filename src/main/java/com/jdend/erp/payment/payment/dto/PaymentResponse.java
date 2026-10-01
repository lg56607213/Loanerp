package com.jdend.erp.payment.payment.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentResponse {
  private Long id;
  private String contractNumber;
  private String customerName;
  private LocalDate paymentDate;
  private Long paymentAmount;
  private String paymentMethod;
  private String companyAccount;
  private String memo;
  private Long voucherId;

  private LocalDate applyDate;
  private String surplusPolicy;
  private String paymentSource;

  /** 이 수납으로 선수금에 쌓인 금액 (초과금을 선수금으로 돌린 경우) */
  private Long prepaidAdded;
}