package com.jdend.erp.loan.writeoff.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.loan.dto.LoanSettlementResponse;
import com.jdend.erp.loan.writeoff.dto.WriteOffRequest;
import com.jdend.erp.loan.writeoff.entity.WriteOff;
import com.jdend.erp.loan.writeoff.service.WriteOffService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/write-offs")
public class WriteOffController {

  private final WriteOffService service;
  private final PermissionService permissionService;

  @GetMapping
  public List<WriteOff> list(@RequestParam(required = false, defaultValue = "") String kw) {
    return service.list(kw);
  }

  @GetMapping("/{id:\\d+}")
  public WriteOff get(@PathVariable Long id) {
    return service.get(id);
  }

  /** 등록 전 상각 대상 금액 미리보기 */
  @GetMapping("/preview")
  public LoanSettlementResponse preview(
      @RequestParam String contractNumber,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate writeOffDate
  ) {
    return service.preview(contractNumber, writeOffDate);
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody WriteOffRequest req, HttpSession session) {
    permissionService.requireManager(session);
    return Map.of("id", service.create(req));
  }

  @DeleteMapping("/{id:\\d+}")
  public void cancel(@PathVariable Long id, HttpSession session) {
    permissionService.requireManager(session);
    service.cancel(id);
  }
}
