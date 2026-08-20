package com.jdend.erp.legal.dto;

import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LegalCaseResponse {
    private Long id;
    private String contractNumber;
    private String customerName;
    private String caseType;
    private String caseNumber;
    private LocalDate registrationDate;
    private Long legalCostPayment;
    private Long legalCostRefund;
    private String status;
    private LocalDateTime createdAt;
    private List<LegalProgressResponse> progressEntries;
    private List<LegalCostItemResponse> costItems;
}
