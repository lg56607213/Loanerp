package com.jdend.erp.dashboard.controller;

import com.jdend.erp.dashboard.dto.*;
import com.jdend.erp.dashboard.service.DashboardService;
import com.jdend.erp.dashboard.dto.LoanPortfolioResponse;
import com.jdend.erp.dashboard.dto.OverdueAgingResponse;
import com.jdend.erp.dashboard.service.LoanDashboardService;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dashboard")

public class DashboardController {

  private final DashboardService service;
  private final LoanDashboardService loanService;

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

  /** 여신 포트폴리오 요약 — 대출잔액·연체율·상각잔액 */
  @GetMapping("/loan-portfolio")
  public LoanPortfolioResponse loanPortfolio() {
    return loanService.portfolio();
  }

  /** 연체 경과기간 분포 */
  @GetMapping("/overdue-aging")
  public OverdueAgingResponse overdueAging() {
    return loanService.overdueAging();
  }

  /** 이번 달 회수 예정·실적 */
  @GetMapping("/monthly-due")
  public Map<String, Object> monthlyDue(
      @RequestParam(required = false) LocalDate baseDate) {
    return loanService.monthlyDue(baseDate);
  }
}
