package com.jdend.erp.payment.overdue.controller;

import com.jdend.erp.payment.overdue.dto.OverdueRowResponse;
import com.jdend.erp.payment.overdue.service.OverdueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/overdue")
public class OverdueController {

    private final OverdueService service;

    /**
     * @param contractNumber 주면 그 채권의 연체 회차만 돌려준다.
     *   종합관리 화면처럼 채권 하나만 보는 곳에서 전체를 받아 걸러낼 필요가 없게 한다.
     */
    @GetMapping
    public List<OverdueRowResponse> list(
            @RequestParam(value = "contractNumber", required = false) String contractNumber
    ) {
        List<OverdueRowResponse> all = service.overdueList();
        if (contractNumber == null || contractNumber.isBlank()) return all;

        String cn = contractNumber.trim();
        return all.stream().filter(r -> cn.equals(r.getContractNumber())).toList();
    }
}
