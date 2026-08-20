package com.jdend.erp.loan.acceleration.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.loan.acceleration.dto.AccelerationRequest;
import com.jdend.erp.loan.acceleration.entity.AccelerationEvent;
import com.jdend.erp.loan.acceleration.service.AccelerationService;
import com.jdend.erp.loan.dto.LoanSettlementResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/accelerations")
public class AccelerationController {

  private final AccelerationService service;
  private final PermissionService permissionService;

  @GetMapping
  public List<AccelerationEvent> list(@RequestParam(required = false, defaultValue = "") String kw) {
    return service.list(kw);
  }

  @GetMapping("/{id:\\d+}")
  public AccelerationEvent get(@PathVariable Long id) {
    return service.get(id);
  }

  /** 등록 전 청구액 미리보기 */
  @GetMapping("/preview")
  public LoanSettlementResponse preview(
      @RequestParam String contractNumber,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eodDate
  ) {
    return service.preview(contractNumber, eodDate);
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody AccelerationRequest req, HttpSession session) {
    permissionService.requireManager(session);
    return Map.of("id", service.create(req));
  }

  @DeleteMapping("/{id:\\d+}")
  public void cancel(@PathVariable Long id, HttpSession session) {
    permissionService.requireManager(session);
    service.cancel(id);
  }
}
