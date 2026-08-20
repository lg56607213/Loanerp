package com.jdend.erp.payment.overdue.dto;

import lombok.*;
import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class OverdueRowResponse {
    private String contractNumber;
    private String customerName;
    private Integer installmentNo;
    private LocalDate paymentDate;
    private Long rentAmount;
    private Long paidAmount;
    private Long unpaidAmount;
    private Integer overdueDays;
}
