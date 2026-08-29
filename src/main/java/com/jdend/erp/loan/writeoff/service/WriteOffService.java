package com.jdend.erp.loan.writeoff.service;

import com.jdend.erp.accounting.voucher.dto.VoucherCreateRequest;
import com.jdend.erp.accounting.voucher.service.VoucherService;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.loan.dto.LoanSettlementResponse;
import com.jdend.erp.loan.service.LoanSettlementService;
import com.jdend.erp.loan.writeoff.dto.WriteOffRequest;
import com.jdend.erp.loan.writeoff.entity.WriteOff;
import com.jdend.erp.loan.support.LoanReceivableAccount;
import com.jdend.erp.loan.writeoff.repository.WriteOffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 대손상각 등록·취소.
 *
 * 등록하면 채권 상태가 '상각'이 되고 이후 이자·지연배상금 기산이 멈춘다.
 * 정상 채권은 상각할 수 없고, 연체 또는 해지(기한이익상실) 상태에서만 가능하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WriteOffService {

  /** 대손충당금 (자산 차감) */
  // 대손충당금·대여금 계정은 계약의 대출기간에 따라 단기/장기로 갈린다.
  //   -> LoanReceivableAccount.allowanceCodeOf(contract) / codeOf(contract)
  /** 대손상각비 (판매비와관리비) */
  private static final String ACC_EXPENSE = "500215";


  private final WriteOffRepository repo;
  private final ContractRepository contractRepo;
  private final LoanSettlementService settlementService;
  private final VoucherService voucherService;

  @Transactional(readOnly = true)
  public List<WriteOff> list(String kw) {
    return repo.search(kw == null ? "" : kw.trim());
  }

  @Transactional(readOnly = true)
  public WriteOff get(Long id) {
    return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("대손상각 내역 없음: " + id));
  }

  /** 등록 전 미리보기 — 상각 대상 금액을 먼저 확인시킨다. */
  @Transactional(readOnly = true)
  public LoanSettlementResponse preview(String contractNumber, LocalDate writeOffDate) {
    return settlementService.settle(contractNumber, writeOffDate);
  }

  @Transactional
  public Long create(WriteOffRequest req) {
    if (req.getContractNumber() == null || req.getContractNumber().isBlank()) {
      throw new IllegalArgumentException("채권번호는 필수입니다.");
    }
    String cn = req.getContractNumber().trim();
    LocalDate date = req.getWriteOffDate() != null ? req.getWriteOffDate() : LocalDate.now();

    Contract c = contractRepo.findWithCustomerByContractNumber(cn)
        .orElseThrow(() -> new IllegalArgumentException("채권 없음: " + cn));

    if (repo.existsByContractNumber(cn)) {
      throw new IllegalArgumentException("이미 상각된 채권입니다: " + cn);
    }
    // 정상 채권 상각 불가 — 연체 또는 해지 상태여야 한다.
    String current = derivedStatusForWriteOff(c);
    if (!ContractStatus.canWriteOff(current)) {
      throw new IllegalArgumentException(
          "대손상각은 연체 또는 해지(기한이익상실) 채권만 가능합니다. 현재 상태: " + current);
    }

    LoanSettlementResponse s = settlementService.settle(cn, date);

    long principal = nz(s.getRemainingPrincipal());
    long interest = nz(s.getUnpaidInterest()) + nz(s.getAccruedInterest());
    long overdue = nz(s.getOverdueInterest());
    long total = principal + interest + overdue;

    long allowanceUsed = Math.max(0L, nz(req.getAllowanceUsed()));
    if (allowanceUsed > total) allowanceUsed = total;
    long expense = total - allowanceUsed;

    WriteOff w = WriteOff.builder()
        .contractId(c.getId())
        .contractNumber(cn)
        .customerName(c.getCustomer() != null ? c.getCustomer().getCustomerName() : null)
        .writeOffDate(date)
        .reason(req.getReason() == null || req.getReason().isBlank()
            ? WriteOff.REASON_UNCOLLECTIBLE : req.getReason())
        .writeOffPrincipal(principal)
        .writeOffInterest(interest)
        .writeOffOverdue(overdue)
        .allowanceUsed(allowanceUsed)
        .expenseAmount(expense)
        .totalWrittenOff(total)
        .prevStatus(c.getStatus())
        .memo(req.getMemo())
        .build();

    WriteOff saved = repo.save(w);

    if (Boolean.TRUE.equals(req.getCreateVoucher()) && principal > 0) {
      Long voucherId = createWriteOffVoucher(saved);
      if (voucherId != null) {
        saved.setVoucherId(voucherId);
        repo.save(saved);
      }
    }

    c.setStatus(ContractStatus.WRITTEN_OFF);
    contractRepo.save(c);

    log.info("대손상각 등록: {} 상각일={} 원금={} 이자={} 지연배상금={} 충당금상계={} 상각비={}",
        cn, date, principal, interest, overdue, allowanceUsed, expense);

    return saved.getId();
  }

  @Transactional
  public void cancel(Long id) {
    WriteOff w = get(id);
    contractRepo.findByContractNumber(w.getContractNumber()).ifPresent(c -> {
      c.setStatus(w.getPrevStatus() == null || w.getPrevStatus().isBlank()
          ? ContractStatus.OVERDUE : w.getPrevStatus());
      contractRepo.save(c);
    });
    repo.delete(w);
    log.info("대손상각 취소: {}", w.getContractNumber());
  }

  /**
   * 상각 분개.
   *   (차) 대손충당금 + 대손상각비  /  (대) 단기/장기대여금
   * 상각 대상 이자는 아직 수익으로 인식하지 않았으므로(현금주의) 원금만 제각한다.
   */
  private Long createWriteOffVoucher(WriteOff w) {
    try {
      // 실행 전표에서 쓴 대여금 계정과 짝을 맞춘다. 단기로 실행한 채권을
      // 장기대여금에서 제각하면 두 계정 모두 잔액이 틀어진다.
      Contract c = contractRepo.findByContractNumber(w.getContractNumber()).orElse(null);
      String loanCode = LoanReceivableAccount.codeOf(c);
      String loanName = LoanReceivableAccount.nameOf(c);
      String allowanceCode = LoanReceivableAccount.allowanceCodeOf(c);
      String allowanceName = LoanReceivableAccount.allowanceNameOf(c);

      List<VoucherCreateRequest.VoucherLineRequest> debits = new ArrayList<>();

      long allowance = Math.min(nz(w.getAllowanceUsed()), nz(w.getWriteOffPrincipal()));
      long expense = nz(w.getWriteOffPrincipal()) - allowance;

      if (allowance > 0) {
        debits.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .accountCode(allowanceCode).account(allowanceName)
            .amount(allowance).description("대손상각 충당금 상계").build());
      }
      if (expense > 0) {
        debits.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .accountCode(ACC_EXPENSE).account("대손상각비")
            .amount(expense).description("대손상각비 계상").build());
      }
      if (debits.isEmpty()) return null;

      List<VoucherCreateRequest.VoucherLineRequest> credits = List.of(
          VoucherCreateRequest.VoucherLineRequest.builder()
              .accountCode(loanCode).account(loanName)
              .amount(nz(w.getWriteOffPrincipal())).description("대손상각 원금 제각").build());

      var res = voucherService.create(VoucherCreateRequest.builder()
          .voucherDate(w.getWriteOffDate())
          .contractNumber(w.getContractNumber())
          .memo("대손상각")
          .debitEntries(debits)
          .creditEntries(credits)
          .build());
      return res == null ? null : res.getId();
    } catch (Exception e) {
      log.warn("대손상각 전표 생성 실패 (상각은 그대로 등록됨): {}", e.getMessage());
      return null;
    }
  }

  /**
   * 상각 가능 여부 판정용 상태.
   * 저장된 해지 상태는 그대로 쓰고, 그 외에는 연체로 간주해 등록을 허용하되
   * 정상 채권(미납 없음)은 화면에서 걸러진다.
   */
  private String derivedStatusForWriteOff(Contract c) {
    String stored = c.getStatus();
    if (ContractStatus.ACCELERATED.equals(stored)) return ContractStatus.ACCELERATED;
    if (ContractStatus.WRITTEN_OFF.equals(stored)) return ContractStatus.WRITTEN_OFF;
    if (ContractStatus.CLOSED.equals(stored)) return ContractStatus.CLOSED;
    return ContractStatus.OVERDUE;
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
