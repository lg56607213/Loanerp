package com.jdend.erp.accounting.voucher.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoucherCreateRequest {
    private String voucherNo;
    private LocalDate voucherDate;

    private String contractNumber;
    private String memo;

    private List<VoucherLineRequest> debitEntries;
    private List<VoucherLineRequest> creditEntries;

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VoucherLineRequest {
        private String accountCode; // 계정코드 (우선 사용)
        private String account;     // 계정명 (accountCode 없을 때 fallback / 표시용)
        private Long amount;
        private String description;
    }
}