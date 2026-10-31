package com.jdend.erp.accounting.partnerbalance.controller;

import com.jdend.erp.accounting.partnerbalance.dto.*;
import com.jdend.erp.accounting.partnerbalance.service.PartnerBalanceService;
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
@RequestMapping("/api/partner-balances")
public class PartnerBalanceController {

  private final PartnerBalanceService service;
  private final PermissionService permissionService;

  @GetMapping
  public List<PartnerBalanceRowResponse> search(
      @RequestParam(required = false, defaultValue = "") String balanceType,
      @RequestParam(required = false, defaultValue = "") String kw,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(required = false, defaultValue = "false") boolean openOnly) {
    return service.search(balanceType, kw, startDate, endDate, openOnly);
  }

  /** 거래처별 잔액 요약 */
  @GetMapping("/summary")
  public PartnerBalanceSummaryResponse summary(
      @RequestParam(required = false, defaultValue = "") String balanceType,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(required = false, defaultValue = "true") boolean openOnly) {
    return service.summary(balanceType, startDate, endDate, openOnly);
  }

  @PostMapping
  public PartnerBalanceRowResponse create(@RequestBody PartnerBalanceRequest req) {
    return service.create(req);
  }

  @PutMapping("/{id}")
  public PartnerBalanceRowResponse update(@PathVariable Long id,
                                          @RequestBody PartnerBalanceRequest req) {
    return service.update(id, req);
  }

  /** 회수(지급) 기록 — amount 만큼 누계에 더한다. 음수면 되돌린다. */
  @PostMapping("/{id}/settle")
  public PartnerBalanceRowResponse settle(@PathVariable Long id, @RequestBody SettleRequest req) {
    return service.settle(id, req.getAmount() == null ? 0L : req.getAmount());
  }

  @PostMapping("/delete")
  public void delete(@RequestBody IdsRequest req, HttpSession session) {
    permissionService.requireManager(session);
    service.delete(req.getIds());
  }

  @GetMapping("/template")
  public ResponseEntity<byte[]> template() {
    byte[] body = service.template();
    String name = new String("미수금미지급금_양식.xlsx".getBytes(StandardCharsets.UTF_8),
        StandardCharsets.ISO_8859_1);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(body);
  }

  @PostMapping("/upload/preview")
  public PartnerBalanceUploadResultResponse preview(@RequestParam("file") MultipartFile file) {
    return service.upload(file, true);
  }

  @PostMapping("/upload")
  public PartnerBalanceUploadResultResponse upload(@RequestParam("file") MultipartFile file) {
    return service.upload(file, false);
  }

  @lombok.Getter @lombok.Setter
  public static class SettleRequest {
    private Long amount;
  }

  @lombok.Getter @lombok.Setter
  public static class IdsRequest {
    private List<Long> ids;
  }
}
