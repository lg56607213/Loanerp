package com.jdend.erp.accounting.voucher.dto;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoucherApprovalRowResponse {
    private Long id;
    private LocalDate voucherDate;
    private String voucherNo;

    private String debitAccount;
    private Long debitAmount;
    private String debitDescription;

    private String creditAccount;
    private Long creditAmount;
    private String creditDescription;

    private String status;
    private String contractNumber;

    // 첫 줄인지 여부 (첫 줄만 체크박스/일자/전표번호/상태 표시)
    private boolean showMain;
}