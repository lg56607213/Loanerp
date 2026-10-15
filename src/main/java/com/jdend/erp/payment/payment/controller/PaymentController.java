package com.jdend.erp.payment.payment.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.payment.payment.dto.*;
import com.jdend.erp.payment.payment.service.PaymentService;
import jakarta.servlet.http.HttpSession;
import com.jdend.erp.common.excel.ExcelUploadResultResponse;
import com.jdend.erp.payment.payment.service.PaymentBulkUploadService;
import com.jdend.erp.payment.payment.service.RepaymentHistoryUploadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")

public class PaymentController {

  private final PaymentService service;
  private final PaymentBulkUploadService bulkUploadService;
  private final RepaymentHistoryUploadService repaymentHistoryUploadService;
  private final PermissionService permissionService;

  @GetMapping
  public Page<PaymentResponse> list(
      @RequestParam(value="kw", required=false, defaultValue="") String kw,
      @RequestParam(value="page", required=false, defaultValue="0") int page,
      @RequestParam(value="size", required=false, defaultValue="15") int size
  ) {
    return service.list(kw, page, size);
  }

  @GetMapping("/{id}")
  public PaymentResponse get(@PathVariable Long id) {
    return service.get(id);
  }

  @GetMapping("/by-contract/{contractNumber}")
  public List<PaymentResponse> listByContract(@PathVariable String contractNumber) {
    return service.listByContractNumber(contractNumber);
  }

  @PostMapping
  public PaymentResponse create(@RequestBody PaymentUpsertRequest req) {
    return service.create(req);
  }

  @PutMapping("/{id}")
  public PaymentResponse update(@PathVariable Long id, @RequestBody PaymentUpsertRequest req,
      HttpSession session) {
    permissionService.requireManager(session);
    return service.update(id, req);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable Long id, HttpSession session) {
    permissionService.requireManager(session);
    service.delete(id);
  }

  // ── 엑셀 일괄등록 ────────────────────────────────────────

  @GetMapping("/bulk-upload/template")
  public ResponseEntity<byte[]> bulkUploadTemplate() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=payment_template.xlsx")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(bulkUploadService.template());
  }

  /**
   * @param createVoucher 전표를 만들지 여부. 과거 내역을 옮길 때는 false.
   * @param dryRun true 면 저장하지 않고 몇 건이 되고 어느 행이 걸리는지만 돌려준다.
   */
  @PostMapping("/bulk-upload")
  public ExcelUploadResultResponse bulkUpload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false, defaultValue = "false") boolean createVoucher,
      @RequestParam(required = false, defaultValue = "false") boolean dryRun
  ) {
    return bulkUploadService.upload(file, createVoucher, dryRun);
  }

  // ── 상환내역 (원금·이자를 적힌 그대로 올린다) ──────────────

  @GetMapping("/repayment-history/template")
  public ResponseEntity<byte[]> repaymentHistoryTemplate() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=repayment_history_template.xlsx")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(repaymentHistoryUploadService.template());
  }

  /** 저장하지 않고 검사만 한다. */
  @PostMapping("/repayment-history/preview")
  public ExcelUploadResultResponse repaymentHistoryPreview(
      @RequestParam("file") MultipartFile file, HttpSession session) {
    permissionService.requireManager(session);
    return repaymentHistoryUploadService.upload(file, true);
  }

  @PostMapping("/repayment-history")
  public ExcelUploadResultResponse repaymentHistoryUpload(
      @RequestParam("file") MultipartFile file, HttpSession session) {
    permissionService.requireManager(session);
    return repaymentHistoryUploadService.upload(file, false);
  }
}