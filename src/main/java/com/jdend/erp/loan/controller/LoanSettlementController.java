package com.jdend.erp.loan.controller;

import com.jdend.erp.loan.dto.LoanSettlementResponse;
import com.jdend.erp.loan.service.LoanSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 정산 명세 조회 — 중도상환·완제·기한이익상실 화면에서 청구액을 미리 확인한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/loans")
public class LoanSettlementController {

  private final LoanSettlementService service;

  @GetMapping("/{contractNumber}/settlement")
  public LoanSettlementResponse settlement(
      @PathVariable String contractNumber,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
  ) {
    return service.settle(contractNumber, asOf);
  }
}
