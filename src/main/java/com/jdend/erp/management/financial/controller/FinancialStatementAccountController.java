package com.jdend.erp.management.financial.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.common.excel.ExcelExportService;
import com.jdend.erp.management.financial.dto.FinancialStatementAccountRequest;
import com.jdend.erp.management.financial.dto.FinancialStatementAccountResponse;
import com.jdend.erp.management.financial.dto.FinancialStatementAccountTreeResponse;
import com.jdend.erp.management.financial.dto.FinancialStatementVoucherRowResponse;
import com.jdend.erp.management.financial.service.FinancialStatementAccountService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/financial-statement-accounts")

public class FinancialStatementAccountController {

  private final FinancialStatementAccountService service;
  private final PermissionService permissionService;
  private final ExcelExportService excelExportService;

  @GetMapping
  public List<FinancialStatementAccountResponse> list(@RequestParam String statementType) {
    return service.list(statementType);
  }

  @PostMapping
  public FinancialStatementAccountResponse create(@RequestBody FinancialStatementAccountRequest req) {
    return service.create(req);
  }

  @PutMapping("/{id}")
  public FinancialStatementAccountResponse update(
      @PathVariable Long id,
      @RequestBody FinancialStatementAccountRequest req,
      HttpSession session
  ) {
    permissionService.requireManager(session);
    return service.update(id, req);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable Long id, HttpSession session) {
    permissionService.requireManager(session);
    service.delete(id);
  }

  @GetMapping("/{id}/voucher-rows")
  public List<FinancialStatementVoucherRowResponse> getVoucherRows(
      @PathVariable Long id,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam(required = false) LocalDate endDate
  ) {
    return service.getVoucherRows(id, startDate, endDate);
  }

  @GetMapping("/{id}/voucher-rows/export")
  public ResponseEntity<byte[]> exportVoucherRows(
      @PathVariable Long id,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam(required = false) LocalDate endDate,
      @RequestParam(required = false, defaultValue = "계정") String accountName
  ) {
    String[] headers = {"전표일자", "전표번호", "구분", "계정명", "금액", "적요", "계약번호", "차량번호", "메모", "상태"};
    List<Object[]> rows = service.getVoucherRows(id, startDate, endDate).stream().map(r -> new Object[]{
        r.getVoucherDate(), r.getVoucherNo(),
        "DEBIT".equals(r.getLineType()) ? "차변" : "대변",
        r.getAccountName(), r.getAmount(), r.getDescription(),
        r.getContractNumber(), r.getMemo(), r.getStatus()
    }).collect(Collectors.toList());
    byte[] data = excelExportService.build(accountName + "_전표내역", headers, rows);
    String filename = "voucher_rows_" + id + ".xlsx";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(data);
  }

  @GetMapping("/tree")
  public List<FinancialStatementAccountTreeResponse> tree(@RequestParam String category) {
    return service.tree(category);
  }

  @GetMapping("/leaves")
  public List<FinancialStatementAccountResponse> leaves() {
    return service.leavesForVoucher();
  }

  @PostMapping("/nodes")
  public FinancialStatementAccountResponse createNode(@RequestBody FinancialStatementAccountRequest req) {
    return service.createNode(req);
  }
}