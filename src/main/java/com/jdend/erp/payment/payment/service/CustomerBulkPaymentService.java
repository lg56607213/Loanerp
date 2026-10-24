package com.jdend.erp.payment.payment.service;

import com.jdend.erp.auth.exception.NotFoundException;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.customer.CustomerRepository;
import com.jdend.erp.loan.repayment.RepaymentAllocation;
import com.jdend.erp.loan.repayment.RepaymentPostingService;
import com.jdend.erp.payment.payment.dto.CustomerBulkPaymentRequest;
import com.jdend.erp.payment.payment.dto.CustomerBulkPaymentResponse;
import com.jdend.erp.payment.payment.dto.PaymentResponse;
import com.jdend.erp.payment.payment.dto.PaymentUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 고객 단위 일괄수납.
 *
 * <p>채무자는 채권 하나하나에 맞춰 보내지 않는다. 한 번에 얼마를 보내면 그 돈이
 * 여러 채권에 나뉘어 들어간다. 그런데 ERP 는 채권 한 건씩만 받을 수 있어서,
 * 받는 사람이 손으로 쪼개 넣어야 했다. 쪼개는 방법이 사람마다 달라지면 장부가
 * 흔들린다.
 *
 * <p><b>나누는 순서는 변제충당 그대로다 — 비용 → 지연배상금 → 이자 → 원금.</b>
 * 채권이 여럿이면 각 단계 안에서 <b>오래된 대출부터</b> 채운다.
 * 100만원을 받았는데 전체 미납 이자가 80만원이면, 80만원이 먼저 이자로 들어가고
 * 남은 20만원이 가장 오래된 대출의 원금으로 간다.
 *
 * <p>각 채권에 '얼마를 넣을지'만 여기서 정하고, 그 채권 안에서 비용·이자·원금을
 * 어떻게 가르는지는 기존 수납 등록에 맡긴다. 같은 계산을 두 벌 두지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class CustomerBulkPaymentService {

  /** 채권이 그날까지 받아야 할 몫을 알아내려고 넣어 보는 큰 금액 */
  private static final long PROBE = 1_000_000_000_000L;

  private final CustomerRepository customerRepo;
  private final ContractRepository contractRepo;
  private final RepaymentPostingService repaymentPosting;
  private final PaymentService paymentService;

  /** 저장하지 않고 어떻게 나뉘는지만 본다. */
  @Transactional(readOnly = true)
  public CustomerBulkPaymentResponse preview(CustomerBulkPaymentRequest req) {
    return distribute(req, false);
  }

  /** 나눈 대로 채권별 수납을 등록한다. */
  @Transactional
  public CustomerBulkPaymentResponse apply(CustomerBulkPaymentRequest req) {
    return distribute(req, true);
  }

  // ── 내부 ──────────────────────────────────────────────────

  private CustomerBulkPaymentResponse distribute(CustomerBulkPaymentRequest req, boolean save) {
    String customerNumber = req.getCustomerNumber() == null ? "" : req.getCustomerNumber().trim();
    if (customerNumber.isEmpty()) throw new IllegalArgumentException("고객번호가 비어 있습니다.");

    Customer customer = customerRepo.findByCustomerNumber(customerNumber)
        .orElseThrow(() -> new NotFoundException("고객번호를 찾을 수 없습니다: " + customerNumber));

    long amount = req.getAmount() == null ? 0L : req.getAmount();
    if (amount <= 0) throw new IllegalArgumentException("입금금액은 1원 이상이어야 합니다.");

    LocalDate payDate = req.getPaymentDate() != null ? req.getPaymentDate() : LocalDate.now();
    LocalDate asOf = req.getApplyDate() != null ? req.getApplyDate() : payDate;

    List<Contract> targets = payableContracts(customerNumber);
    if (targets.isEmpty()) {
      throw new IllegalArgumentException(
          "수납할 채권이 없습니다: " + customer.getCustomerName()
          + " — 미회수원금이 남은 채권이 없습니다.");
    }

    // 1) 채권마다 그날까지 받아야 할 몫을 알아낸다.
    //    큰 금액을 넣어 보면 충당이 멈추는 지점이 그 채권의 미회수 전액이다.
    List<Slot> slots = new ArrayList<>();
    for (Contract c : targets) {
      RepaymentAllocation due = repaymentPosting.simulate(c, PROBE, asOf);
      slots.add(new Slot(c, due.getCost(), due.getOverdueInterest(), due.getInterest(), due.getPrincipal()));
    }

    // 2) 변제충당 순서대로, 각 단계 안에서는 오래된 대출부터 채운다.
    long rest = amount;
    rest = fill(slots, rest, Step.COST);
    rest = fill(slots, rest, Step.OVERDUE);
    rest = fill(slots, rest, Step.INTEREST);
    rest = fill(slots, rest, Step.PRINCIPAL);

    // 3) 채권별로 수납을 등록한다. 채권 안에서의 충당은 기존 경로가 한다.
    List<CustomerBulkPaymentResponse.Line> lines = new ArrayList<>();
    for (Slot s : slots) {
      long take = s.total();
      if (take <= 0) continue;

      Long paymentId = null;
      if (save) {
        PaymentResponse saved = paymentService.create(PaymentUpsertRequest.builder()
            .contractNumber(s.contract.getContractNumber())
            .paymentDate(payDate)
            .applyDate(req.getApplyDate())
            .paymentAmount(take)
            .companyAccount(req.getCompanyAccount())
            .surplusPolicy("원금충당")
            .paymentSource("보통예금")
            .createVoucher(req.getCreateVoucher() == null || req.getCreateVoucher())
            .memo(memoOf(req, customer))
            .build());
        paymentId = saved.getId();
      }

      lines.add(CustomerBulkPaymentResponse.Line.builder()
          .contractNumber(s.contract.getContractNumber())
          .executeDate(executeDateOf(s.contract))
          .loanAmount(s.contract.getLoanAmount())
          .remainingPrincipal(s.contract.getRemainingPrincipal())
          .dueCost(s.dueCost).dueOverdueInterest(s.dueOverdue)
          .dueInterest(s.dueInterest).duePrincipal(s.duePrincipal)
          .paidCost(s.cost).paidOverdueInterest(s.overdue)
          .paidInterest(s.interest).paidPrincipal(s.principal)
          .paidTotal(take)
          .paymentId(paymentId)
          .build());
    }

    long allocated = lines.stream().mapToLong(CustomerBulkPaymentResponse.Line::getPaidTotal).sum();
    return CustomerBulkPaymentResponse.builder()
        .customerNumber(customerNumber)
        .customerName(customer.getCustomerName())
        .paymentDate(payDate)
        .amount(amount)
        .allocated(allocated)
        .excess(Math.max(0L, amount - allocated))
        .saved(save)
        .lines(lines)
        .build();
  }

  /** 수납 대상 채권 — 미회수원금이 남아 있고 상각·종료가 아닌 것, 오래된 순. */
  private List<Contract> payableContracts(String customerNumber) {
    return contractRepo.findByCustomerNumberOrderByIdAsc(customerNumber).stream()
        .filter(c -> !ContractStatus.EXCLUDED_FROM_OVERDUE.contains(c.getStatus()))
        .filter(c -> c.getRemainingPrincipal() != null && c.getRemainingPrincipal() > 0)
        .sorted(Comparator.comparing(CustomerBulkPaymentService::executeDateOf,
            Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Contract::getContractNumber))
        .toList();
  }

  private static LocalDate executeDateOf(Contract c) {
    return c.getExecuteDate() != null ? c.getExecuteDate() : c.getStartDate();
  }

  private String memoOf(CustomerBulkPaymentRequest req, Customer customer) {
    String base = "일괄수납 " + customer.getCustomerName();
    String memo = req.getMemo() == null ? "" : req.getMemo().trim();
    return memo.isEmpty() ? base : base + " / " + memo;
  }

  private enum Step { COST, OVERDUE, INTEREST, PRINCIPAL }

  /** 한 단계를 오래된 대출부터 채우고 남은 금액을 돌려준다. */
  private long fill(List<Slot> slots, long rest, Step step) {
    for (Slot s : slots) {
      if (rest <= 0) break;
      long due = switch (step) {
        case COST -> s.dueCost - s.cost;
        case OVERDUE -> s.dueOverdue - s.overdue;
        case INTEREST -> s.dueInterest - s.interest;
        case PRINCIPAL -> s.duePrincipal - s.principal;
      };
      if (due <= 0) continue;

      long take = Math.min(rest, due);
      switch (step) {
        case COST -> s.cost += take;
        case OVERDUE -> s.overdue += take;
        case INTEREST -> s.interest += take;
        case PRINCIPAL -> s.principal += take;
      }
      rest -= take;
    }
    return rest;
  }

  /** 채권 한 건의 '받아야 할 몫'과 '이번에 넣는 몫' */
  private static final class Slot {
    final Contract contract;
    final long dueCost, dueOverdue, dueInterest, duePrincipal;
    long cost, overdue, interest, principal;

    Slot(Contract contract, long dueCost, long dueOverdue, long dueInterest, long duePrincipal) {
      this.contract = contract;
      this.dueCost = dueCost;
      this.dueOverdue = dueOverdue;
      this.dueInterest = dueInterest;
      this.duePrincipal = duePrincipal;
    }

    long total() { return cost + overdue + interest + principal; }
  }
}
