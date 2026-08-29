package com.jdend.erp.loan.interest;

import java.time.LocalDate;

/**
 * 원리금균등 스케줄의 한 회차 (계약상 예정액).
 * 납입 실적이 붙기 전의 순수한 계약 조건이다.
 *
 * @param installmentNo      회차 번호 (1부터)
 * @param dueDate            <b>원래 계약상</b> 납기일. 기한이익상실이 나도 이 값은 바뀌지 않는다.
 * @param scheduledPayment   회차 납입액 (원금 + 이자)
 * @param scheduledPrincipal 회차 원금
 * @param scheduledInterest  회차 약정이자
 * @param openingPrincipal   회차 시작 시점의 잔여원금 (이자 산정 기준)
 * @param closingPrincipal   회차 종료 시점의 잔여원금
 */
public record ScheduledInstallment(
    int installmentNo,
    LocalDate dueDate,
    long scheduledPayment,
    long scheduledPrincipal,
    long scheduledInterest,
    long openingPrincipal,
    long closingPrincipal
) {}
