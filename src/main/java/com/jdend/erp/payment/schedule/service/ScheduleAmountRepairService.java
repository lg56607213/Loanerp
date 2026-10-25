package com.jdend.erp.payment.schedule.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.loan.repayment.RepaymentPostingService;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상환스케줄의 회차 금액을 계약 조건대로 다시 맞춘다.
 *
 * <p>계약을 수정하면 {@code regenerate()} 가 스케줄을 다시 만드는데, 과거 수납을
 * 지키려고 <b>오늘 이후 회차만</b> 손댄다. 그래서 이미 만기가 가까운 채권은
 * 월납입액을 실제 약정액(백원단위)으로 고쳐도 회차 청구액이 옛 금액으로 남았다
 * — 계약은 111,500원이라 하고 스케줄은 111,491원이라 하는 상태가 된다.
 *
 * <p>여기서는 행을 지우지 않는다. 청구(billing)가 스케줄 id 를 참조하므로
 * 지우고 다시 만들면 그 연결이 끊긴다. 회차 번호로 맞춰 금액만 바꿔 넣고,
 * 충당 실적은 수납을 다시 흘려보내({@code recompute}) 새 금액 위에서 계산한다.
 *
 * <p>손대는 것은 <b>회차 원금 몫</b>뿐이다. 이자와 청구액, 회차별 잔액은
 * {@code recompute} 가 '그 기간에 실제로 남아 있던 원금' 으로 다시 매긴다 —
 * 계획상 금액으로 비교하면 조기상환이나 일할 계산 때문에 멀쩡한 채권까지
 * 어긋난 것으로 잡힌다(78건 중 52건이 그랬다). 약정 납입액이 달라지면 원금
 * 몫이 반드시 달라지므로, 원금 몫만 보면 고칠 곳을 정확히 짚는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleAmountRepairService {

  private final ContractRepository contractRepo;
  private final PaymentScheduleRepository scheduleRepo;
  private final PaymentScheduleAutoGeneratorService autoGen;
  private final RepaymentPostingService repaymentPosting;

  /** 한 채권의 복구 결과 — 바뀐 회차 수와 1회차 청구액의 전/후. */
  public record Result(String contractNumber, String customerName,
                       int rows, long rentBefore, long rentAfter) {}

  /**
   * @param dryRun true 면 고치지 않고 어디가 어긋났는지만 센다.
   * @param only   채권번호 하나만 손볼 때. 비우면 전 채권.
   */
  @Transactional
  public List<Result> repair(boolean dryRun, String only) {
    List<Result> out = new ArrayList<>();

    for (Contract c : contractRepo.findAll()) {
      if (only != null && !only.isBlank() && !only.equals(c.getContractNumber())) continue;

      List<PaymentSchedule> rows = scheduleRepo
          .findByContractNumberOrderByInstallmentNoAsc(c.getContractNumber());
      if (rows.isEmpty()) continue;

      // 계약 조건으로 스케줄을 다시 계산한다 — 저장하는 것과 같은 build() 다.
      Map<Integer, PaymentSchedule> wanted = new LinkedHashMap<>();
      for (PaymentSchedule ps : autoGen.preview(c)) {
        if (ps.getInstallmentNo() != null) wanted.put(ps.getInstallmentNo(), ps);
      }
      if (wanted.isEmpty()) continue;

      List<PaymentSchedule> dirty = new ArrayList<>();
      int wrong = 0;
      long before = 0, after = 0;

      for (PaymentSchedule ps : rows) {
        PaymentSchedule w = wanted.get(ps.getInstallmentNo());
        if (w == null) continue;  // 회차 수가 줄어든 경우 — 남은 행은 그대로 둔다

        if (nz(ps.getPrincipalAmount()) == nz(w.getPrincipalAmount())) continue;
        wrong++;
        if (wrong == 1) {
          before = nz(ps.getRentAmount());
          after = nz(w.getRentAmount());
        }

        // 점검만 할 때는 손대지 않는다. 영속 엔티티는 값을 바꾸는 순간
        // 트랜잭션이 끝날 때 그대로 저장되므로 '보기만' 하려면 건드려선 안 된다.
        if (!dryRun) {
          // 원금·이자·청구액을 한 묶음으로 넣어 서로 어긋나지 않게 한다.
          // 이자와 청구액은 바로 뒤 recompute 가 실제 잔액 기준으로 다시 매긴다.
          ps.setPrincipalAmount(w.getPrincipalAmount());
          ps.setInterestAmount(w.getInterestAmount());
          ps.setRentAmount(w.getRentAmount());
          dirty.add(ps);
        }
      }

      if (wrong > 0) {
        out.add(new Result(c.getContractNumber(),
            c.getCustomer() == null ? null : c.getCustomer().getCustomerName(),
            wrong, before, after));
        if (!dryRun) {
          scheduleRepo.saveAll(dirty);
          // 새 회차 금액 위에서 충당을 다시 계산한다 — 미납·잔여원금이 따라간다.
          repaymentPosting.recompute(c.getContractNumber());
        }
      }
    }

    log.info("[ScheduleAmountRepair] {} 채권의 회차 금액 {}",
        out.size(), dryRun ? "확인(저장 안 함)" : "복구");
    return out;
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
