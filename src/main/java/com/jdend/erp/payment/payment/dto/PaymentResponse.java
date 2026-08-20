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
}