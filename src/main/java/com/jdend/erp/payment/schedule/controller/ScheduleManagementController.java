package com.jdend.erp.payment.schedule.controller;

import com.jdend.erp.payment.schedule.dto.ScheduleSaveRequest;
import com.jdend.erp.payment.schedule.dto.ScheduleSearchResponse;
import com.jdend.erp.payment.schedule.service.ScheduleManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/schedules")

public class ScheduleManagementController {

  private final ScheduleManagementService service;

  @GetMapping("/by-contract-number")
  public ScheduleSearchResponse getByContractNumber(@RequestParam String contractNumber) {
    return service.getByContractNumber(contractNumber);
  }

  @PutMapping
  public void save(@RequestBody ScheduleSaveRequest req) {
    service.save(req);
  }
}