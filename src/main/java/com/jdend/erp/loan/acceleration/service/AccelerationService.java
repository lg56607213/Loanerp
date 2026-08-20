package com.jdend.erp.loan.acceleration.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.loan.acceleration.dto.AccelerationRequest;
import com.jdend.erp.loan.acceleration.entity.AccelerationEvent;
import com.jdend.erp.loan.acceleration.repository.AccelerationEventRepository;
import com.jdend.erp.loan.dto.LoanSettlementResponse;
import com.jdend.erp.loan.service.LoanSettlementService;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 기한이익상실 등록·취소.
 *
 * 등록하면 채권 상태가 '해지'가 되고 잔여원금 전액이 즉시 청구 대상이 된다.
 * 미래 회차는 '청구중지'로 바꿔 개별 청구를 멈춘다 — 잔여원금을 일괄로 청구하는데
 * 회차 청구까지 살아 있으면 원금이 이중으로 잡힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccelerationService {

  private final AccelerationEventRepository repo;
  private final ContractRepository contractRepo;
  private final PaymentScheduleRepository scheduleRepo;
  private final LoanSettlementService settlementService;

  @Transactional(readOnly = true)
  public List<AccelerationEvent> list(String kw) {
    return repo.search(kw == null ? "" : kw.trim());
  }

  @Transactional(readOnly = true)
  public AccelerationEvent get(Long id) {
    return repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("기한이익상실 내역 없음: " + id));
  }

  /** 등록 전 미리보기 — 화면에서 청구 금액을 먼저 확인시킨다. */
  @Transactional(readOnly = true)
  public LoanSettlementResponse preview(String contractNumber, LocalDate eodDate) {
    return settlementService.settle(contractNumber, eodDate);
  }

  @Transactional
  public Long create(AccelerationRequest req) {
    if (req.getContractNumber() == null || req.getContractNumber().isBlank()) {
      throw new IllegalArgumentException("채권번호는 필수입니다.");
    }
    String cn = req.getContractNumber().trim();
    LocalDate eodDate = req.getEodDate() != null ? req.getEodDate() : LocalDate.now();

    Contract c = contractRepo.findWithCustomerByContractNumber(cn)
        .orElseThrow(() -> new IllegalArgumentException("채권 없음: " + cn));

    if (ContractStatus.WRITTEN_OFF.equals(c.getStatus())) {
      throw new IllegalArgumentException("이미 상각된 채권은 기한이익상실을 등록할 수 없습니다.");
    }
    if (ContractStatus.CLOSED.equals(c.getStatus())) {
      throw new IllegalArgumentException("이미 종료된 채권은 기한이익상실을 등록할 수 없습니다.");
    }
    if (repo.existsByContractNumber(cn)) {
      throw new IllegalArgumentException("이미 기한이익상실이 등록된 채권입니다: " + cn);
    }
    if (c.getStartDate() != null && eodDate.isBefore(c.getStartDate())) {
      throw new IllegalArgumentException("기한이익상실일은 대출 시작일(" + c.getStartDate() + ") 이후여야 합니다.");
    }

    // 상실일 기준 청구액을 일할로 확정한다.
    LoanSettlementResponse s = settlementService.settle(cn, eodDate);

    long calledPrincipal = nz(s.getRemainingPrincipal());
    long accruedInterest = nz(s.getUnpaidInterest()) + nz(s.getAccruedInterest());
    long accruedOverdue = nz(s.getOverdueInterest());

    // 미래 회차 청구 중지
    int suspended = suspendFutureInstallments(cn, eodDate);

    AccelerationEvent e = AccelerationEvent.builder()
        .contractId(c.getId())
        .contractNumber(cn)
        .customerName(c.getCustomer() != null ? c.getCustomer().getCustomerName() : null)
        .eodDate(eodDate)
        .noticeDate(req.getNoticeDate())
        .reason(req.getReason() == null || req.getReason().isBlank()
            ? AccelerationEvent.REASON_OVERDUE : req.getReason())
        .calledPrincipal(calledPrincipal)
        .accruedInterest(accruedInterest)
        .accruedOverdue(accruedOverdue)
        .totalCalled(calledPrincipal + accruedInterest + accruedOverdue)
        .suspendedInstallments(suspended)
        .memo(req.getMemo())
        .build();

    AccelerationEvent saved = repo.save(e);

    c.setStatus(ContractStatus.ACCELERATED);
    contractRepo.save(c);

    log.info("기한이익상실 등록: {} 상실일={} 청구원금={} 미수이자={} 지연배상금={} 청구중지회차={}",
        cn, eodDate, calledPrincipal, accruedInterest, accruedOverdue, suspended);

    return saved.getId();
  }

  /**
   * 기한이익상실 취소 — 잘못 등록한 경우에만 쓴다.
   * 청구중지했던 회차를 되살리고 채권 상태를 정상으로 되돌린다.
   */
  @Transactional
  public void cancel(Long id) {
    AccelerationEvent e = get(id);

    Contract c = contractRepo.findByContractNumber(e.getContractNumber()).orElse(null);
    if (c != null) {
      if (ContractStatus.WRITTEN_OFF.equals(c.getStatus())) {
        throw new IllegalArgumentException("이미 상각된 채권입니다. 대손상각을 먼저 취소해주세요.");
      }
      c.setStatus(ContractStatus.NORMAL);
      contractRepo.save(c);
    }

    for (PaymentSchedule ps : scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(e.getContractNumber())) {
      if (PaymentSchedule.LINE_SUSPENDED.equals(ps.getLineStatus())) {
        ps.setLineStatus(PaymentSchedule.LINE_UNPAID);
        ps.refreshLineStatus();
      }
    }
    repo.delete(e);
    log.info("기한이익상실 취소: {}", e.getContractNumber());
  }

  /** 상실일 이후 납입예정 회차를 청구중지로 전환한다. */
  private int suspendFutureInstallments(String contractNumber, LocalDate eodDate) {
    int n = 0;
    for (PaymentSchedule ps : scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(contractNumber)) {
      LocalDate due = ps.getPaymentDate() != null ? ps.getPaymentDate() : ps.getTaxInvoiceDate();
      if (due != null && due.isAfter(eodDate)) {
        ps.setLineStatus(PaymentSchedule.LINE_SUSPENDED);
        n++;
      }
    }
    return n;
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
