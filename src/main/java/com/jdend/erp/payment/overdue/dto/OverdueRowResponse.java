package com.jdend.erp.payment.overdue.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 연체현황 한 줄 — <b>채권 한 건</b>이다.
 *
 * <p>전에는 미납 회차마다 한 줄이었다. 한 채권이 열두 줄로 늘어져 몇 건이 연체인지
 * 세어지지 않았고, 같은 채무자가 화면을 가득 채웠다. 추심은 채권 단위로 하지
 * 회차 단위로 하지 않는다. 회차별 내역은 상환스케줄 화면에서 본다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverdueRowResponse {

    private String contractNumber;
    private String customerName;

    /** 가장 오래된 미납 회차의 납입예정일 — 연체일수는 이 날부터 센다. */
    private LocalDate firstOverdueDate;

    /** 오늘 − 최초 연체일 */
    private Integer overdueDays;

    /** 미납 회차 수 */
    private Integer overdueCount;

    /** 미납 원금 합계 */
    private Long overduePrincipal;

    /** 미납 이자 합계 */
    private Long overdueInterest;

    /** 미납 합계(원금+이자) */
    private Long overdueAmount;

    /** 지연배상금 — 미납 원금에 연체이율로 붙은 금액 */
    private Long lateFee;

    /** 미회수원금 — 추심 금액을 가늠할 때 함께 본다 */
    private Long remainingPrincipal;

    // ── 예전 화면이 쓰던 칸 (지금은 채권 단위라 의미가 겹친다) ──
    /** @deprecated 채권 단위로 바뀌어 회차 번호가 없다. 미납 회차 수를 쓴다. */
    @Deprecated
    private Integer installmentNo;
    /** @deprecated 최초 연체일로 대체됐다. */
    @Deprecated
    private LocalDate paymentDate;
    private Long rentAmount;
    private Long paidAmount;
    private Long unpaidAmount;
}
