package com.jdend.erp.payment.schedule.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.RepaymentMethod;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.loan.repayment.RepaymentPostingService;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 만기일시 채권에 '만기 회차'를 세우고 만기일을 맞춘다.
 *
 * <p>만기일시는 매회 이자만 받고 원금은 만기에 받는다. 그런데 스케줄이 원금을
 * 마지막 이자 회차에 얹어 놓아서, 만기가 아직 한 달 남았는데도 원금이 미납으로
 * 잡히고 그 날부터 지연배상금이 붙었다. 4억을 빌려준 건은 그 때문에 연체가
 * 23일 일찍 시작돼 지연배상금이 453만원 더 붙었다.
 *
 * <p>만기는 <b>최초납부일 + 회차수(개월)</b>다. 10월 15일에 내주고 10월 25일부터
 * 12개월 이자를 받기로 했으면 원금은 이듬해 10월 25일에 받는다.
 *
 * <p>행을 지우지 않는다. 청구(billing)가 스케줄 id 를 참조하므로 지우고 다시
 * 만들면 그 연결이 끊긴다. 회차 번호로 맞춰 날짜와 금액을 바꿔 넣고, 없는
 * 만기 회차만 새로 더한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulletMaturityRepairService {

  private final ContractRepository contractRepo;
  private final PaymentScheduleRepository scheduleRepo;
  private final PaymentScheduleAutoGeneratorService autoGen;
  private final RepaymentPostingService repaymentPosting;

  /** 한 채권의 결과 — 바뀐 회차 수, 더한 회차 수, 만기일 전/후. */
  public record Result(String contractNumber, String customerName,
                       int changed, int added,
                       LocalDate endBefore, LocalDate endAfter) {}

  /**
   * @param dryRun true 면 고치지 않고 어디가 어긋났는지만 센다.
   * @param only   채권번호 하나만 손볼 때. 비우면 만기일시 전 채권.
   */
  @Transactional
  public List<Result> repair(boolean dryRun, String only) {
    List<Result> out = new ArrayList<>();

    for (Contract c : contractRepo.findAll()) {
      if (!RepaymentMethod.BULLET.equals(c.getRepaymentMethod())) continue;
      if (only != null && !only.isBlank() && !only.equals(c.getContractNumber())) continue;

      List<PaymentSchedule> rows = scheduleRepo
          .findByContractNumberOrderByInstallmentNoAsc(c.getContractNumber());
      if (rows.isEmpty()) continue;

      // 계약 조건으로 스케줄을 다시 계산한다 — 저장하는 것과 같은 build() 라,
      // 만기 회차가 하나 더 붙은 모습으로 나온다.
      Map<Integer, PaymentSchedule> wanted = new LinkedHashMap<>();
      for (PaymentSchedule ps : autoGen.preview(c)) {
        if (ps.getInstallmentNo() != null) wanted.put(ps.getInstallmentNo(), ps);
      }
      if (wanted.isEmpty()) continue;

      Map<Integer, PaymentSchedule> have = new LinkedHashMap<>();
      for (PaymentSchedule ps : rows) {
        if (ps.getInstallmentNo() != null) have.put(ps.getInstallmentNo(), ps);
      }

      List<PaymentSchedule> dirty = new ArrayList<>();
      int changed = 0, added = 0;

      for (Map.Entry<Integer, PaymentSchedule> e : wanted.entrySet()) {
        PaymentSchedule w = e.getValue();
        PaymentSchedule ps = have.get(e.getKey());

        if (ps == null) {
          added++;
          // 점검만 할 때는 만들지 않는다.
          if (!dryRun) dirty.add(w);
          continue;
        }

        if (same(ps, w)) continue;
        changed++;

        // 점검만 할 때는 손대지 않는다. 영속 엔티티는 값을 바꾸는 순간
        // 트랜잭션이 끝날 때 그대로 저장된다.
        if (!dryRun) {
          ps.setPaymentDate(w.getPaymentDate());
          ps.setTaxInvoiceDate(w.getTaxInvoiceDate());
          ps.setBillStartDate(w.getBillStartDate());
          ps.setBillEndDate(w.getBillEndDate());
          ps.setRentAmount(w.getRentAmount());
          ps.setPrincipalAmount(w.getPrincipalAmount());
          ps.setInterestAmount(w.getInterestAmount());
          dirty.add(ps);
        }
      }

      // 만기일은 마지막 회차의 납입예정일이다 — 최초납부일 + 회차수(개월).
      LocalDate endAfter = wanted.values().stream()
          .reduce((a, b) -> b).map(PaymentSchedule::getPaymentDate).orElse(null);
      LocalDate endBefore = c.getEndDate();
      boolean endChanged = endAfter != null && !endAfter.equals(endBefore);

      if (changed > 0 || added > 0 || endChanged) {
        out.add(new Result(c.getContractNumber(),
            c.getCustomer() == null ? null : c.getCustomer().getCustomerName(),
            changed, added, endBefore, endAfter));

        if (!dryRun) {
          if (!dirty.isEmpty()) scheduleRepo.saveAll(dirty);
          if (endChanged) {
            c.setEndDate(endAfter);
            contractRepo.save(c);
          }
          // 새 회차 위에서 충당을 다시 계산한다 — 미납·잔여원금이 따라간다.
          repaymentPosting.recompute(c.getContractNumber());
        }
      }
    }

    log.info("[BulletMaturityRepair] 만기일시 {} 채권 {}",
        out.size(), dryRun ? "확인(저장 안 함)" : "복구");
    return out;
  }

  private static boolean same(PaymentSchedule a, PaymentSchedule b) {
    return eq(a.getPaymentDate(), b.getPaymentDate())
        && nz(a.getRentAmount()) == nz(b.getRentAmount())
        && nz(a.getPrincipalAmount()) == nz(b.getPrincipalAmount())
        && nz(a.getInterestAmount()) == nz(b.getInterestAmount());
  }

  private static boolean eq(Object a, Object b) {
    return a == null ? b == null : a.equals(b);
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
