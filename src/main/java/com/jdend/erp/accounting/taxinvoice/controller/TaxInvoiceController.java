package com.jdend.erp.accounting.taxinvoice.controller;

import com.jdend.erp.accounting.taxinvoice.dto.*;
import com.jdend.erp.accounting.taxinvoice.service.TaxInvoiceReconcileService;
import com.jdend.erp.accounting.taxinvoice.service.TaxInvoiceService;
import com.jdend.erp.auth.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tax-invoices")
public class TaxInvoiceController {

  private final TaxInvoiceService service;
  private final TaxInvoiceReconcileService reconcileService;
  private final PermissionService permissionService;

  @GetMapping
  public List<TaxInvoiceRowResponse> search(
      @RequestParam(required = false, defaultValue = "") String invoiceType,
      @RequestParam(required = false, defaultValue = "") String kw,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
    return service.search(invoiceType, kw, startDate, endDate);
  }

  @PostMapping
  public TaxInvoiceRowResponse create(@RequestBody TaxInvoiceRequest req) {
    return service.create(req);
  }

  @PutMapping("/{id}")
  public TaxInvoiceRowResponse update(@PathVariable Long id, @RequestBody TaxInvoiceRequest req) {
    return service.update(id, req);
  }

  @PostMapping("/delete")
  public void delete(@RequestBody IdsRequest req, HttpSession session) {
    permissionService.requireManager(session);
    service.delete(req.getIds());
  }

  // ── 엑셀 ────────────────────────────────────────────────

  @GetMapping("/template")
  public ResponseEntity<byte[]> template() {
    byte[] body = service.template();
    String name = new String("세금계산서_양식.xlsx".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(body);
  }

  /** 저장 전에 무엇이 들어가고 무엇이 빠지는지 먼저 보여준다. */
  @PostMapping("/upload/preview")
  public TaxInvoiceUploadResultResponse preview(@RequestParam("file") MultipartFile file) {
    return service.upload(file, true);
  }

  @PostMapping("/upload")
  public TaxInvoiceUploadResultResponse upload(@RequestParam("file") MultipartFile file) {
    return service.upload(file, false);
  }

  // ── 3자 대조 ─────────────────────────────────────────────

  /**
   * 세금계산서 ↔ 통장 ↔ 전표 대조.
   *
   * @param dayWindow   세금계산서 작성일과 입출금일 사이 허용 일수 (기본 90)
   * @param matchByName 통장 적요에 거래처명이 들어 있어야 짝으로 볼지 (기본 true)
   * @param minAmount   이 금액 미만의 통장 거래는 무시 (기본 0)
   */
  @GetMapping("/reconcile")
  public ReconcileResponse reconcile(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(required = false, defaultValue = "90") int dayWindow,
      @RequestParam(required = false, defaultValue = "true") boolean matchByName,
      @RequestParam(required = false, defaultValue = "0") long minAmount) {
    return reconcileService.reconcile(startDate, endDate, dayWindow, matchByName, minAmount);
  }

  @lombok.Getter @lombok.Setter
  public static class IdsRequest {
    private List<Long> ids;
  }
}
