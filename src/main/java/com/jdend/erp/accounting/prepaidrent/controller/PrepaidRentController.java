package com.jdend.erp.accounting.prepaidrent.controller;

import com.jdend.erp.accounting.prepaidrent.dto.PrepaidRentCreateRequest;
import com.jdend.erp.accounting.prepaidrent.dto.PrepaidRentHistoryResponse;
import com.jdend.erp.accounting.prepaidrent.dto.PrepaidRentListItemResponse;
import com.jdend.erp.accounting.prepaidrent.service.PrepaidRentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prepaid-rents")
@RequiredArgsConstructor
public class PrepaidRentController {

    private final PrepaidRentService service;

    /**
     * GET /api/prepaid-rents
     * 선수금 계약 목록 (선수금 거래가 1건 이상 있는 계약)
     */
    @GetMapping
    public ResponseEntity<List<PrepaidRentListItemResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "") String customerName) {
        return ResponseEntity.ok(service.list(startDate, endDate, customerName));
    }

    /**
     * GET /api/prepaid-rents/{contractId}/history
     * 특정 계약의 선수금 거래 이력
     */
    @GetMapping("/{contractId}/history")
    public ResponseEntity<List<PrepaidRentHistoryResponse>> history(@PathVariable Long contractId) {
        return ResponseEntity.ok(service.history(contractId));
    }

    /**
     * GET /api/prepaid-rents/{contractId}/schedules
     * 선수금 수납 화면용 납부 스케줄 조회 (연체/납부/미납 상태 포함)
     */
    @GetMapping("/{contractId}/schedules")
    public ResponseEntity<List<Map<String, Object>>> schedules(@PathVariable Long contractId) {
        return ResponseEntity.ok(service.getSchedules(contractId));
    }

    /**
     * POST /api/prepaid-rents/deposit
     * 선수금 입금 등록 (고객이 미래 상환분을 미리 납입)
     * 전표: 차변 보통예금 / 대변 선수금
     */
    @PostMapping("/deposit")
    public ResponseEntity<Map<String, String>> deposit(@RequestBody PrepaidRentCreateRequest req) {
        service.deposit(req);
        return ResponseEntity.ok(Map.of("message", "선수금 입금이 등록되었습니다."));
    }

    /**
     * POST /api/prepaid-rents/apply
     * 선수금 적용 등록 (당월 청구액에 선수금 차감, 수익 인식)
     * 전표: 차변 선수금 / 대변 임대수익
     */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, String>> apply(@RequestBody PrepaidRentCreateRequest req) {
        service.apply(req);
        return ResponseEntity.ok(Map.of("message", "선수금 적용이 등록되었습니다."));
    }
}
