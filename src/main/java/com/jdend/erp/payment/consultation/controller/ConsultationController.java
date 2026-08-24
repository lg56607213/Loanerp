package com.jdend.erp.payment.consultation.controller;

import com.jdend.erp.payment.consultation.dto.*;
import com.jdend.erp.payment.consultation.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/consultations")

public class ConsultationController {

  private final ConsultationService service;

  // ✅ 계약정보(채권번호/고객명) + 유효성 체크
  // GET /api/consultations/contract-summary?contractNumber=R00001001
  @GetMapping("/contract-summary")
  public ContractSummaryResponse contractSummary(@RequestParam String contractNumber) {
    return service.contractSummary(contractNumber);
  }

  // ✅ 상담 이력
  // GET /api/consultations?contractNumber=R00001001
  @GetMapping
  public List<ConsultationResponse> history(@RequestParam String contractNumber) {
    return service.history(contractNumber);
  }

  // ✅ 등록
  @PostMapping
  public ConsultationResponse create(@RequestBody ConsultationCreateRequest req) {
    return service.create(req);
  }

  // ✅ 삭제
  @DeleteMapping("/{id}")
  public void delete(@PathVariable Long id) {
    service.delete(id);
  }
}