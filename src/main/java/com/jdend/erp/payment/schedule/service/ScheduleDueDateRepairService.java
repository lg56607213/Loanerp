package com.jdend.erp.payment.schedule.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.PaymentDayType;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상환스케줄의 납입예정일을 계약 조건대로 다시 맞춘다.
 *
 * <p>예전 수납 처리가 '납부 완료' 표시로 PaymentSchedule.paymentDate 를 입금일로
 * 바꿔 썼다. 그 칸은 <b>납입예정일</b>이라 변제충당과 연체 판정의 기준이 되는데,
 * 수납이 들어올 때마다 실제 입금일로 바뀌고 수납을 지우면 비어 버렸다.
 * 원인은 고쳤지만 이미 어긋난 날짜는 되돌려야 한다.
 *
 * <p>되돌리는 규칙은 스케줄을 처음 만들 때와 같다 — 시작일에서 n개월 뒤의
 * 약정 납입일(말일이면 그 달의 마지막 날). 회차 번호만 보면 되므로 충당 실적은
 * 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleDueDateRepairService {

  private final ContractRepository contractRepo;
  private final PaymentScheduleRepository scheduleRepo;

  /**
   * @param dryRun true 면 고치지 않고 몇 건이 어긋났는지만 센다.
   * @return 채권번호 → 바뀐 회차 수 (바뀔 것이 없으면 들어가지 않는다)
   */
  @Transactional
  public Map<String, Integer> repair(boolean dryRun) {
    Map<String, Integer> changed = new LinkedHashMap<>();

    for (Contract c : contractRepo.findAll()) {
      LocalDate start = c.getStartDate();
      if (start == null) continue;

      List<PaymentSchedule> rows = scheduleRepo
          .findByContractNumberOrderByInstallmentNoAsc(c.getContractNumber());
      if (rows.isEmpty()) continue;

      boolean lastDay = PaymentDayType.isLastDay(c.getPaymentDayType());
      int payDay = (c.getPaymentDay() != null && c.getPaymentDay() > 0)
          ? c.getPaymentDay() : start.getDayOfMonth();

      List<PaymentSchedule> dirty = new ArrayList<>();
      for (PaymentSchedule ps : rows) {
        Integer no = ps.getInstallmentNo();
        if (no == null || no <= 0) continue;

        LocalDate dueMonth = start.plusMonths(no);
        LocalDate due = lastDay ? lastDayOf(dueMonth) : withDaySafe(dueMonth, payDay);

        if (!due.equals(ps.getPaymentDate())) {
          ps.setPaymentDate(due);
          dirty.add(ps);
        }
      }

      if (!dirty.isEmpty()) {
        changed.put(c.getContractNumber(), dirty.size());
        if (!dryRun) scheduleRepo.saveAll(dirty);
      }
    }

    log.info("[ScheduleDueDateRepair] {} 채권의 납입예정일 {}",
        changed.size(), dryRun ? "확인(저장 안 함)" : "복구");
    return changed;
  }

  private static LocalDate lastDayOf(LocalDate baseMonth) {
    return baseMonth.withDayOfMonth(baseMonth.lengthOfMonth());
  }

  private static LocalDate withDaySafe(LocalDate baseMonth, int day) {
    return baseMonth.withDayOfMonth(Math.min(day, baseMonth.lengthOfMonth()));
  }

  /** 회차 번호가 비어 있는 등 손볼 수 없는 스케줄이 있는지 본다. */
  @Transactional(readOnly = true)
  public List<String> brokenContracts() {
    List<String> out = new ArrayList<>();
    for (Contract c : contractRepo.findAll()) {
      List<PaymentSchedule> rows = scheduleRepo
          .findByContractNumberOrderByInstallmentNoAsc(c.getContractNumber());
      boolean bad = rows.stream().anyMatch(ps -> ps.getInstallmentNo() == null)
          || (c.getStartDate() == null && !rows.isEmpty());
      if (bad) out.add(c.getContractNumber());
    }
    out.sort(Comparator.naturalOrder());
    return out;
  }
}
