package com.jdend.erp.accounting.voucher.controller;

import com.jdend.erp.contract.dto.ContractSearchRowResponse;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/accounting/lookup/contracts")

public class ContractSearchController {

    private final ContractRepository contractRepo;

    /** ✅ 회계/전표용 계약 검색 (충돌 방지 URL) */
    // GET /api/accounting/lookup/contracts/search?kw=
    @GetMapping("/search")
    public List<ContractSearchRowResponse> search(
            @RequestParam(value = "kw", required = false, defaultValue = "") String kw
    ) {
        String keyword = (kw == null) ? "" : kw.trim();
        List<Contract> list = contractRepo.searchTop200(keyword);

        List<ContractSearchRowResponse> out = new ArrayList<>();
        for (Contract c : list) {
            String customerName = (c.getCustomer() != null) ? c.getCustomer().getCustomerName() : null;

            out.add(ContractSearchRowResponse.builder()
                    .contractNumber(c.getContractNumber())
                    .customerName(customerName)
                    .loanType(c.getLoanType())
                    .startDate(c.getStartDate())
                    .endDate(c.getEndDate())
                    .loanAmount(c.getLoanAmount())
                    .interestRate(c.getInterestRate())
                    .monthlyPayment(c.getMonthlyPayment())
                    .remainingPrincipal(c.getRemainingPrincipal())
                    .build()
            );
        }
        return out;
    }
}