package com.jdend.erp.loan.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.loan.dto.LoanSettlementResponse;
import com.jdend.erp.loan.repayment.RepaymentPostingService;
import com.jdend.erp.loan.service.LoanSettlementService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 정산 명세 조회 — 중도상환·완제·기한이익상실 화면에서 청구액을 미리 확인한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/loans")
public class LoanSettlementController {

  private final LoanSettlementService service;
  private final RepaymentPostingService repaymentPosting;
  private final ContractRepository contractRepo;
  private final PermissionService permissionService;

  @GetMapping("/{contractNumber}/settlement")
  public LoanSettlementResponse settlement(
      @PathVariable String contractNumber,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
  ) {
    return service.settle(contractNumber, asOf);
  }

  /**
   * 변제충당 재계산 — 어긋난 잔여원금을 실제 수납 기록으로 되돌린다.
   *
   * 남아 있는 수납을 날짜순으로 다시 흘려보내 충당을 처음부터 계산하므로,
   * 수납이 없으면 충당 실적이 0 으로 돌아가고 잔여원금은 대출금 전액이 된다.
   *
   * 과거에 전표를 직접 지워 수납 기록만 사라지고 충당 실적이 남은 채권을
   * 되돌리는 용도다(지금은 자동 생성 전표의 단독 삭제를 막아 다시 생기지 않는다).
   * 수납 기록 자체는 건드리지 않으므로 여러 번 눌러도 결과가 같다.
   */
  @PostMapping("/{contractNumber}/recompute")
  public Map<String, Object> recompute(@PathVariable String contractNumber, HttpSession session) {
    permissionService.requireManager(session);

    Contract before = contractRepo.findByContractNumber(contractNumber)
        .orElseThrow(() -> new IllegalArgumentException("채권 없음: " + contractNumber));
    Long beforeRemaining = before.getRemainingPrincipal();

    repaymentPosting.recompute(contractNumber);

    Contract after = contractRepo.findByContractNumber(contractNumber).orElse(before);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("contractNumber", contractNumber);
    result.put("loanAmount", after.getLoanAmount());
    result.put("remainingPrincipalBefore", beforeRemaining);
    result.put("remainingPrincipalAfter", after.getRemainingPrincipal());
    return result;
  }
}
