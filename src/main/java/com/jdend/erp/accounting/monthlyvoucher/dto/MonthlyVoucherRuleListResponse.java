package com.jdend.erp.accounting.monthlyvoucher.dto;

import com.jdend.erp.accounting.voucher.entity.MonthlyVoucherRule;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class MonthlyVoucherRuleListResponse {
    private Long id;
    private boolean active;
    private String contractNumber;
    private Integer monthlyDay;
    private LocalDate nextRunDate;
    private LocalDate lastRunDate;
    private String debitAccount;
    private String debitAccountCode;
    private Long debitAmount;
    private String debitDescription;
    private String creditAccount;
    private String creditAccountCode;
    private Long creditAmount;
    private String creditDescription;
    private String memo;

    public static MonthlyVoucherRuleListResponse from(MonthlyVoucherRule r) {
        return MonthlyVoucherRuleListResponse.builder()
                .id(r.getId())
                .active(r.isActive())
                .contractNumber(r.getContractNumber())
                .monthlyDay(r.getMonthlyDay())
                .nextRunDate(r.getNextRunDate())
                .lastRunDate(r.getLastRunDate())
                .debitAccount(r.getDebitAccount())
                .debitAccountCode(r.getDebitAccountCode())
                .debitAmount(r.getDebitAmount())
                .debitDescription(r.getDebitDescription())
                .creditAccount(r.getCreditAccount())
                .creditAccountCode(r.getCreditAccountCode())
                .creditAmount(r.getCreditAmount())
                .creditDescription(r.getCreditDescription())
                .memo(r.getMemo())
                .build();
    }
}
