package com.jdend.erp.dashboard.controller;

import com.jdend.erp.dashboard.dto.*;
import com.jdend.erp.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dashboard")

public class DashboardController {

  private final DashboardService service;

  @GetMapping("/cash-daily")
  public DashboardCashResponse cashDaily(@RequestParam(required = false) LocalDate baseDate) {
    return service.cashDaily(baseDate);
  }



  @GetMapping("/maturity-soon")
  public List<DashboardMaturityRow> maturitySoon(
      @RequestParam(defaultValue = "30") int days,
      @RequestParam(defaultValue = "5") int limit
  ) {
    return service.maturitySoon(days, limit);
  }

  @GetMapping("/receivables-top")
  public List<DashboardReceivableRow> receivablesTop(@RequestParam(defaultValue = "5") int limit) {
    return service.receivablesTop(limit);
  }

  @GetMapping("/bank-summary")
  public List<DashboardBankSummaryRow> bankSummary() {
    return service.bankSummary();
  }

  @GetMapping("/bank-voucher-diff")
  public List<DashboardBankDiffRow> bankVoucherDiff() {
    return service.bankVoucherDiff();
  }



  @GetMapping("/pending-vouchers")
  public DashboardPendingVoucherResponse pendingVouchers() {
    return service.pendingVoucherSummary();
  }
}