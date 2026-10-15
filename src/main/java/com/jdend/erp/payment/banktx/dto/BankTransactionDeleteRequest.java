package com.jdend.erp.payment.banktx.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** 삭제할 은행내역 id 목록 */
@Getter @Setter
@NoArgsConstructor
public class BankTransactionDeleteRequest {
  private List<Long> ids;
}
