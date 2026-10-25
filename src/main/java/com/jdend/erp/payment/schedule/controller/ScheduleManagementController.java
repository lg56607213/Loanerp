package com.jdend.erp.payment.schedule.controller;

import com.jdend.erp.payment.schedule.dto.ScheduleSaveRequest;
import com.jdend.erp.payment.schedule.dto.ScheduleSearchResponse;
import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.payment.schedule.service.ScheduleAmountRepairService;
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
  private final ScheduleAmountRepairService amountRepair;
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

  /**
   * 회차 청구액을 계약 조건대로 다시 맞춘다.
   *
   * 계약 수정은 '오늘 이후 회차'만 다시 만들어서, 만기가 가까운 채권은 월납입액을
   * 실제 약정액으로 고쳐도 스케줄이 옛 금액으로 남았다. 행은 지우지 않고 금액만
   * 바꿔 넣은 뒤 수납을 다시 흘려보낸다.
   *
   * @param dryRun 기본 true — 먼저 어디가 어긋났는지 보고 나서 고친다.
   * @param contractNumber 한 건만 손볼 때
   */
  @PostMapping("/repair-amounts")
  public Map<String, Object> repairAmounts(
      @RequestParam(required = false, defaultValue = "true") boolean dryRun,
      @RequestParam(required = false) String contractNumber,
      HttpSession session) {
    permissionService.requireManager(session);
    var changed = amountRepair.repair(dryRun, contractNumber);
    return Map.of(
        "dryRun", dryRun,
        "contracts", changed.size(),
        "rows", changed.stream().mapToInt(ScheduleAmountRepairService.Result::rows).sum(),
        "detail", changed);
  }
}
