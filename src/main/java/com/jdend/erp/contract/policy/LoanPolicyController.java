package com.jdend.erp.contract.policy;

import com.jdend.erp.auth.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/loan-policy")
public class LoanPolicyController {

  private final LoanPolicyService service;
  private final PermissionService permissionService;

  @GetMapping
  public Map<String, Object> get() {
    LoanPolicySettings s = service.get();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("maxInterestRate", s.getMaxInterestRate());
    out.put("reason", s.getReason());
    out.put("useAgreedMonthly", s.useAgreedMonthly());
    out.put("updatedAt", s.getUpdatedAt());
    return out;
  }

  @PutMapping
  public Map<String, Object> save(@RequestBody Request req, HttpSession session) {
    permissionService.requireManager(session);
    service.save(req.getMaxInterestRate(), req.getReason(), req.getUseAgreedMonthly());
    return get();
  }

  @lombok.Getter @lombok.Setter
  public static class Request {
    private BigDecimal maxInterestRate;
    private String reason;
    private Boolean useAgreedMonthly;
  }
}
