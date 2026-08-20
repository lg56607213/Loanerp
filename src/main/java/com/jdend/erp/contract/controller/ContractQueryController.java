package com.jdend.erp.contract.controller;

import com.jdend.erp.contract.dto.ContractSearchRowResponse;
import com.jdend.erp.contract.dto.ContractStatusRowResponse;
import com.jdend.erp.contract.dto.ContractSummaryResponse;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.payment.overdue.service.OverdueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/contracts")
public class ContractQueryController {

  private final ContractRepository contractRepo;
  private final OverdueService overdueService;

  /** 전체 채권 검색 (선택 모달용) */
  @GetMapping("/search")
  public List<ContractSearchRowResponse> search(
      @RequestParam(value = "kw", required = false, defaultValue = "") String kw
  ) {
    return toSearchRows(contractRepo.searchTop200(kw == null ? "" : kw.trim()));
  }

  /** 수납등록 가능한 채권만 검색 */
  @GetMapping("/payable-search")
  public List<ContractSearchRowResponse> payableSearch(
      @RequestParam(value = "kw", required = false, defaultValue = "") String kw
  ) {
    return toSearchRows(contractRepo.payableSearchTop200(kw == null ? "" : kw.trim()));
  }

  private List<ContractSearchRowResponse> toSearchRows(List<Contract> list) {
    List<ContractSearchRowResponse> out = new ArrayList<>();
    for (Contract c : list) {
      out.add(ContractSearchRowResponse.builder()
          .contractNumber(c.getContractNumber())
          .customerName(c.getCustomer() != null ? c.getCustomer().getCustomerName() : null)
          .loanType(c.getLoanType())
          .startDate(c.getStartDate())
          .endDate(c.getEndDate())
          .loanAmount(c.getLoanAmount())
          .interestRate(c.getInterestRate())
          .monthlyPayment(c.getMonthlyPayment())
          .remainingPrincipal(c.getRemainingPrincipal())
          .build());
    }
    return out;
  }

  /** 수납 화면 채권 요약 */
  @GetMapping("/{contractNumber}/summary")
  public ContractSummaryResponse summary(@PathVariable String contractNumber) {
    String cn = (contractNumber == null) ? "" : contractNumber.trim();

    Contract c = contractRepo.findWithCustomerByContractNumber(cn)
        .orElseThrow(() -> new RuntimeException("채권 없음: " + cn));

    Customer cu = c.getCustomer();

    String email = null;
    if (cu != null) {
      email = (cu.getBillEmail() != null && !cu.getBillEmail().isBlank())
          ? cu.getBillEmail()
          : cu.getManagerEmail();
    }

    return ContractSummaryResponse.builder()
        .contractNumber(c.getContractNumber())
        .customerName(cu != null ? cu.getCustomerName() : null)
        .registrationNumber(cu != null ? cu.getRegistrationNumber() : null)
        .email(email)
        .monthlyPayment(c.getMonthlyPayment())
        .contractStatus(c.getStatus() == null || c.getStatus().isBlank()
            ? ContractStatus.NORMAL : c.getStatus())
        .build();
  }

  /**
   * 채권현황.
   *
   * 상태는 저장값과 파생값을 나눠 판정한다.
   *  - 해지/상각/종료는 기한이익상실·대손상각·완제 이벤트로 이미 확정 저장된 값이라 그대로 쓴다.
   *  - 그 외에는 미납 스케줄 유무로 정상/연체를 그때그때 계산한다.
   */
  @GetMapping("/status")
  public List<ContractStatusRowResponse> status(
      @RequestParam(required = false, defaultValue = "") String contractNumber,
      @RequestParam(required = false, defaultValue = "") String customerName,
      @RequestParam(required = false, defaultValue = "") String contractStatus
  ) {
    List<ContractStatusRowResponse> list = contractRepo.statusList(
        contractNumber == null ? "" : contractNumber.trim(),
        customerName == null ? "" : customerName.trim()
    );

    LocalDate today = LocalDate.now();
    Set<String> overdueNumbers = overdueService.overdueContractNumbers();

    for (ContractStatusRowResponse row : list) {
      row.setContractStatus(deriveStatus(row, today, overdueNumbers));
    }

    String filter = (contractStatus == null) ? "" : contractStatus.trim();
    if (!filter.isEmpty()) {
      list = list.stream().filter(r -> filter.equals(r.getContractStatus())).toList();
    }
    return list;
  }

  private String deriveStatus(ContractStatusRowResponse row, LocalDate today, Set<String> overdueNumbers) {
    String stored = row.getStatus();

    // 이벤트로 확정된 상태(해지/상각/종료)는 재판정하지 않는다.
    if (stored != null && ContractStatus.STORED.contains(stored)) {
      return stored;
    }
    // 만기가 지났는데 미납이 없으면 종료로 본다.
    boolean overdue = overdueNumbers.contains(row.getContractNumber());
    if (!overdue && row.getContractEnd() != null && row.getContractEnd().isBefore(today)) {
      return ContractStatus.CLOSED;
    }
    return overdue ? ContractStatus.OVERDUE : ContractStatus.NORMAL;
  }
}
