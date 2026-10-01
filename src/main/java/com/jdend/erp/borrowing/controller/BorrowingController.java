package com.jdend.erp.borrowing.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.borrowing.dto.*;
import com.jdend.erp.borrowing.service.BorrowingService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 차입금 관리 — 등록, 상환, 이자지급 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/borrowings")
public class BorrowingController {

  private final BorrowingService service;
  private final PermissionService permissionService;

  // ── 차입금 ────────────────────────────────────────────────

  @GetMapping
  public List<BorrowingResponse> list(@RequestParam(required = false) String keyword) {
    return service.list(keyword);
  }

  @GetMapping("/{borrowingNumber}")
  public BorrowingResponse get(@PathVariable String borrowingNumber) {
    return service.get(borrowingNumber);
  }

  @PostMapping
  public BorrowingResponse create(@RequestBody BorrowingRequest req) {
    return service.create(req);
  }

  @PutMapping("/{id:\\d+}")
  public BorrowingResponse update(@PathVariable Long id, @RequestBody BorrowingRequest req) {
    return service.update(id, req);
  }

  @DeleteMapping("/{id:\\d+}")
  public void delete(@PathVariable Long id, HttpSession session) {
    permissionService.requireManager(session);
    service.delete(id);
  }

  // ── 상환 · 이자지급 ────────────────────────────────────────

  @GetMapping("/{borrowingNumber}/transactions")
  public List<BorrowingTransactionResponse> transactions(@PathVariable String borrowingNumber) {
    return service.transactions(borrowingNumber);
  }

  @GetMapping("/{borrowingNumber}/schedule")
  public List<BorrowingScheduleRowResponse> schedule(@PathVariable String borrowingNumber) {
    return service.schedule(borrowingNumber);
  }

  /** 이자 자동계산 — 화면 금액칸에 미리 채울 제안값 */
  @GetMapping("/{borrowingNumber}/interest-preview")
  public InterestPreviewResponse interestPreview(
      @PathVariable String borrowingNumber,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
  ) {
    return service.previewInterest(borrowingNumber, asOf);
  }

  @PostMapping("/transactions")
  public BorrowingTransactionResponse addTransaction(@RequestBody BorrowingTransactionRequest req) {
    return service.addTransaction(req);
  }

  @DeleteMapping("/transactions/{txId:\\d+}")
  public void deleteTransaction(@PathVariable Long txId) {
    service.deleteTransaction(txId);
  }
}
