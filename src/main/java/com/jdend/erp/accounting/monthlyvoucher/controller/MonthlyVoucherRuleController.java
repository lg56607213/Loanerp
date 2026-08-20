package com.jdend.erp.accounting.monthlyvoucher.controller;

import com.jdend.erp.accounting.monthlyvoucher.dto.MonthlyVoucherRuleCreateRequest;
import com.jdend.erp.accounting.monthlyvoucher.dto.MonthlyVoucherRuleCreateResponse;
import com.jdend.erp.accounting.monthlyvoucher.dto.MonthlyVoucherRuleListResponse;
import com.jdend.erp.accounting.monthlyvoucher.service.MonthlyVoucherRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/accounting/monthly-voucher-rules")
public class MonthlyVoucherRuleController {

  private final MonthlyVoucherRuleService service;

  @PostMapping
  public MonthlyVoucherRuleCreateResponse create(@RequestBody MonthlyVoucherRuleCreateRequest req) {
    return service.create(req);
  }

  @GetMapping
  public List<MonthlyVoucherRuleListResponse> list(
      @RequestParam(required = false) Boolean activeOnly,
      @RequestParam(required = false) String contractNumber
  ) {
    return service.list(activeOnly, contractNumber);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/{id}/toggle")
  public MonthlyVoucherRuleListResponse toggleActive(@PathVariable Long id) {
    return service.toggleActive(id);
  }
}