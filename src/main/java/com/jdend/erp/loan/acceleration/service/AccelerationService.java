package com.jdend.erp.loan.acceleration.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.entity.RepaymentMethod;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.loan.acceleration.dto.AccelerationRequest;
import com.jdend.erp.loan.acceleration.entity.AccelerationEvent;
import com.jdend.erp.loan.acceleration.repository.AccelerationEventRepository;
import com.jdend.erp.loan.dto.LoanSettlementResponse;
import com.jdend.erp.loan.service.LoanSettlementService;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.loan.repayment.RepaymentPostingService;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import com.jdend.erp.payment.schedule.service.PaymentScheduleAutoGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 기한이익상실 등록·취소.
 *
 * <p>등록하면 채권 상태가 '해지'가 되고 잔여원금 전액이 즉시 청구 대상이 된다.
 * 분할 상환은 끝났으므로 <b>상환방식을 '만기일시'로 바꾸고</b>, 아직 납기일이 오지 않은
 * 회차들을 <b>'일시청구' 1건으로 접는다.</b> 회차 청구가 살아 있으면 원금이 이중으로 잡힌다.
 *
 * <p>접은 회차의 원금은 그대로 옮겨 오므로 잔여원금은 변하지 않는다.
 * 다만 그 원금에는 <b>약정이자도 지연배상금도 붙지 않는다</b> —
 * 조기 상환된 구간의 이자는 발생하지 않았고, 원래 납기일이 오지 않은 원금에
 * 연체가산이자를 붙이는 것은 개인채무자보호법이 막는다.
 * 경과이자는 정산(LoanSettlementService)이 잔여원금 전체에 약정이율로 따로 계산한다.
 *
 * <p>이미 납기일이 지난 미납 회차는 <b>건드리지 않는다.</b> 원래 납기일이 남아 있어야
 * 연체일수와 지연배상금이 정확히 계산된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccelerationService {

  private final AccelerationEventRepository repo;
  private final ContractRepository contractRepo;
  private final PaymentScheduleRepository scheduleRepo;
  private final LoanSettlementService settlementService;
  private final PaymentScheduleAutoGeneratorService scheduleAutoGen;
  private final RepaymentPostingService repaymentPosting;

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

    // 미도래 회차를 '일시청구' 1건으로 접는다
    int collapsed = collapseFutureInstallments(cn, eodDate);
    String previousMethod = c.getRepaymentMethod();

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
        .suspendedInstallments(collapsed)
        .previousRepaymentMethod(previousMethod)
        .memo(req.getMemo())
        .build();

    AccelerationEvent saved = repo.save(e);

    c.setStatus(ContractStatus.ACCELERATED);
    // 분할 상환이 끝났으므로 상환방식도 실제와 맞춘다.
    // 스케줄은 위에서 이미 접었으므로 여기서 재생성하면 안 된다(재생성하면 접은 게 풀린다).
    c.setRepaymentMethod(RepaymentMethod.BULLET);
    contractRepo.save(c);

    log.info("기한이익상실 등록: {} 상실일={} 청구원금={} 미수이자={} 지연배상금={} 접은회차={} 상환방식 {}->만기일시",
        cn, eodDate, calledPrincipal, accruedInterest, accruedOverdue, collapsed, previousMethod);

    return saved.getId();
  }

  /**
   * 기한이익상실 취소 — 잘못 등록한 경우에만 쓴다.
   *
   * <p>접었던 회차를 되살려야 하는데, 부분 납입이 섞여 있으면 원래대로 쪼개는 것이
   * 간단하지 않다. 그래서 스케줄을 통째로 다시 만들고 수납을 처음부터 다시 흘려보낸다
   * (RepaymentPostingService 가 이미 replay 방식이라 이렇게 하면 정확히 복원된다).
   */
  @Transactional
  public void cancel(Long id) {
    AccelerationEvent e = get(id);
    String cn = e.getContractNumber();

    Contract c = contractRepo.findByContractNumber(cn).orElse(null);
    if (c != null) {
      if (ContractStatus.WRITTEN_OFF.equals(c.getStatus())) {
        throw new IllegalArgumentException("이미 상각된 채권입니다. 대손상각을 먼저 취소해주세요.");
      }
      c.setStatus(ContractStatus.NORMAL);
      if (e.getPreviousRepaymentMethod() != null && !e.getPreviousRepaymentMethod().isBlank()) {
        c.setRepaymentMethod(e.getPreviousRepaymentMethod());
      }
      contractRepo.save(c);

      // 원래 상환방식으로 스케줄을 다시 만든 뒤 수납을 재계산한다
      scheduleRepo.deleteByContractNumber(cn);
      scheduleAutoGen.ensureGenerated(c);
      repaymentPosting.recompute(cn);
    } else {
      // 계약이 없으면 남은 청구중지 표시만 되돌린다 (예전 방식으로 등록된 건)
      for (PaymentSchedule ps : scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(cn)) {
        if (ps.isAcceleratedLine()) {
          ps.setLineStatus(PaymentSchedule.LINE_UNPAID);
          ps.refreshLineStatus();
        }
      }
    }

    repo.delete(e);
    log.info("기한이익상실 취소: {} 상환방식 원복={}", cn, e.getPreviousRepaymentMethod());
  }

  /**
   * 상실일 기준 미도래 회차를 '일시청구' 1건으로 접는다.
   *
   * <p>접은 회차들의 예정원금과 이미 낸 원금을 그대로 옮겨 미납원금이 변하지 않게 한다.
   * 새 회차의 이자는 0이다 — 조기 상환된 구간의 약정이자는 발생하지 않았기 때문이다.
   *
   * <p>미도래 회차에는 이자가 충당된 적이 없다(변제충당이 도래 회차에만 이자를 넣는다).
   * 그래서 원금만 옮기면 실적이 누락되지 않는다.
   *
   * @return 접힌 회차 수
   */
  private int collapseFutureInstallments(String contractNumber, LocalDate eodDate) {
    List<PaymentSchedule> all = scheduleRepo.findByContractNumberOrderByInstallmentNoAsc(contractNumber);

    List<PaymentSchedule> future = new ArrayList<>();
    for (PaymentSchedule ps : all) {
      LocalDate due = ps.getPaymentDate() != null ? ps.getPaymentDate() : ps.getTaxInvoiceDate();
      if (due != null && due.isAfter(eodDate)) future.add(ps);
    }
    if (future.isEmpty()) return 0;

    long scheduledPrincipal = 0L;
    long paidPrincipal = 0L;
    for (PaymentSchedule ps : future) {
      scheduledPrincipal += nz(ps.getPrincipalAmount());
      paidPrincipal += nz(ps.getPaidPrincipal());
    }

    int no = future.stream().map(PaymentSchedule::getInstallmentNo)
        .filter(java.util.Objects::nonNull)
        .min(Comparator.naturalOrder()).orElse(1);

    scheduleRepo.deleteAll(future);
    scheduleRepo.flush();

    PaymentSchedule called = PaymentSchedule.builder()
        .contractNumber(contractNumber)
        .installmentNo(no)
        .billStartDate(eodDate)
        .billEndDate(eodDate)
        .paymentDate(eodDate)
        .taxInvoiceDate(eodDate)
        .principalAmount(scheduledPrincipal)
        .interestAmount(0L)
        .remainingPrincipal(0L)
        .paidPrincipal(paidPrincipal)
        .paidInterest(0L)
        .paidOverdueInterest(0L)
        .paidCost(0L)
        .lineStatus(PaymentSchedule.LINE_CALLED)
        .build();
    scheduleRepo.save(called);

    return future.size();
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
