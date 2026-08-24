package com.jdend.erp.accounting.prepaidrent.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class PrepaidRentListItemResponse {

    private Long contractId;
    private String contractNumber;
    private String customerName;

    /** 계약의 월 납입액 (적용 시 기본 금액) */
    private Long monthlyRent;

    /** 선수금 잔액 = sum(입금) - sum(적용) */
    private Long balance;

    /** 가장 최근 입금일 */
    private LocalDate lastDepositDate;
}
