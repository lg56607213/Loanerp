package com.jdend.erp.payment.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 고객 단위 일괄수납 — 한 번 받은 돈을 그 고객의 여러 채권에 나눠 넣는다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBulkPaymentRequest {

  private String customerNumber;

  /** 입금일 */
  private LocalDate paymentDate;

  /** 충당기준일 — 비우면 입금일을 쓴다. */
  private LocalDate applyDate;

  /** 받은 금액 전체 */
  private Long amount;

  /** 전표를 만들지 — 비우면 만든다. 지난 내역을 옮길 때는 false. */
  private Boolean createVoucher;

  /** 입금된 당사 계좌 */
  private String companyAccount;

  private String memo;
}
