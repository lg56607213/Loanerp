package com.jdend.erp.payment.schedule.controller;

import com.jdend.erp.payment.schedule.dto.ScheduleSaveRequest;
import com.jdend.erp.payment.schedule.dto.ScheduleSearchResponse;
import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.payment.schedule.service.ScheduleDueDateRepairService;
import com.jdend.erp.payment.schedule.service.ScheduleManagementService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/schedules")

public class ScheduleManagementController {

  private final ScheduleManagementService service;
  private final ScheduleDueDateRepairService dueDateRepair;
  private final PermissionService permissionService;

  @GetMapping("/by-contract-number")
  public ScheduleSearchResponse getByContractNumber(@RequestParam String contractNumber) {
    return service.getByContractNumber(contractNumber);
  }

  @PutMapping
  public void save(@RequestBody ScheduleSaveRequest req) {
    service.save(req);
  }

  /**
   * 납입예정일을 계약 조건대로 다시 맞춘다.
   *
   * 예전 수납 처리가 납입예정일 칸을 '납부 완료' 표시로 덮어써서 날짜가 어긋난
   * 스케줄을 되돌린다. 충당 실적은 건드리지 않는다.
   *
   * @param dryRun 기본 true — 먼저 몇 건이 어긋났는지 보고 나서 고친다.
   */
  @PostMapping("/repair-due-dates")
  public Map<String, Object> repairDueDates(
      @RequestParam(required = false, defaultValue = "true") boolean dryRun,
      HttpSession session) {
    permissionService.requireManager(session);
    Map<String, Integer> changed = dueDateRepair.repair(dryRun);
    return Map.of(
        "dryRun", dryRun,
        "contracts", changed.size(),
        "rows", changed.values().stream().mapToInt(Integer::intValue).sum(),
        "detail", changed);
  }
}