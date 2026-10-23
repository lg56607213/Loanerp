package com.jdend.erp.payment.overdue.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.contract.support.DailyInterestCalculator;
import com.jdend.erp.payment.overdue.dto.OverdueRowResponse;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 연체현황.
 *
 * <p><b>판정 기준은 회차별 충당 실적</b>이다. 상환스케줄의 paidPrincipal·paidInterest 는
 * 수납이 들어올 때마다 변제충당으로 다시 계산돼 저장된다. 연체는 그 결과를 그대로 읽는다.
 *
 * <p>전에는 이 서비스가 따로 계산했다. 채권의 총 수납액을 1회차부터 청구액만큼 차례로
 * 채우는 방식이었는데, 실제 충당과 달라 멀쩡한 채권이 연체로 잡혔다. 만기일시로 이자만
 * 받는 채권이 특히 그랬다 — 이자는 매달 들어왔는데도 총액을 앞 회차부터 채우다 보니
 * 뒤쪽 회차가 미납으로 남았다. 같은 사실을 두 군데서 다르게 계산하면 언젠가 어긋난다.
 *
 * <p>미회수원금이 0 인 채권은 연체로 보지 않는다. 다 갚은 채권이다. 스케줄에 미납으로
 * 남은 회차가 있어도(예: 중도에 몰아서 갚아 뒤 회차가 비어 있는 경우) 받을 돈은 없다.
 */
@Service
@RequiredArgsConstructor
public class OverdueService {

    private final ContractRepository contractRepo;
    private final PaymentScheduleRepository scheduleRepo;

    /** 연체 채권 목록 — 채권 한 건이 한 줄이다. */
    @Transactional(readOnly = true)
    public List<OverdueRowResponse> overdueList() {
        LocalDate today = LocalDate.now();
        List<OverdueRowResponse> result = new ArrayList<>();

        for (Map.Entry<Contract, List<PaymentSchedule>> e : overdueCandidates(today).entrySet()) {
            Contract c = e.getKey();
            List<PaymentSchedule> overdueLines = e.getValue();

            LocalDate first = overdueLines.get(0).getPaymentDate();
            long principal = overdueLines.stream().mapToLong(PaymentSchedule::unpaidPrincipal).sum();
            long interest = overdueLines.stream().mapToLong(PaymentSchedule::unpaidInterest).sum();

            result.add(OverdueRowResponse.builder()
                    .contractNumber(c.getContractNumber())
                    .customerName(c.getCustomer() != null ? c.getCustomer().getCustomerName() : null)
                    .firstOverdueDate(first)
                    .overdueDays((int) ChronoUnit.DAYS.between(first, today))
                    .overdueCount(overdueLines.size())
                    .overduePrincipal(principal)
                    .overdueInterest(interest)
                    .overdueAmount(principal + interest)
                    .lateFee(lateFeeOf(c, overdueLines, today))
                    .remainingPrincipal(nz(c.getRemainingPrincipal()))
                    // 예전 화면 호환 — 채권 단위 합계를 그대로 넣는다
                    .unpaidAmount(principal + interest)
                    .paymentDate(first)
                    .build());
        }

        result.sort(Comparator.comparing(OverdueRowResponse::getOverdueDays).reversed());
        return result;
    }

    /** 연체로 보는 채권번호 — 채권현황의 상태 판정이 쓴다. */
    @Transactional(readOnly = true)
    public Set<String> overdueContractNumbers() {
        return new HashSet<>(overdueCandidates(LocalDate.now()).keySet().stream()
                .map(Contract::getContractNumber)
                .collect(Collectors.toSet()));
    }

    // ── 내부 ──────────────────────────────────────────────────

    /**
     * 연체인 채권과 그 미납 회차들(납입예정일 오름차순).
     *
     * @return 미납 회차가 하나라도 있는 채권만. 없으면 비어 있다.
     */
    private Map<Contract, List<PaymentSchedule>> overdueCandidates(LocalDate today) {
        List<Contract> actives = contractRepo.findAllActiveWithCustomer().stream()
                // 다 갚은 채권은 연체가 아니다
                .filter(c -> nz(c.getRemainingPrincipal()) > 0)
                .filter(c -> c.getContractNumber() != null)
                .toList();
        if (actives.isEmpty()) return Map.of();

        Map<String, List<PaymentSchedule>> byContract =
                scheduleRepo.findByContractNumberIn(actives.stream().map(Contract::getContractNumber).toList())
                        .stream()
                        .collect(Collectors.groupingBy(PaymentSchedule::getContractNumber));

        // 입력 순서를 지키려고 LinkedHashMap 을 쓴다
        Map<Contract, List<PaymentSchedule>> out = new java.util.LinkedHashMap<>();
        for (Contract c : actives) {
            List<PaymentSchedule> overdueLines = byContract.getOrDefault(c.getContractNumber(), List.of())
                    .stream()
                    .filter(ps -> !ps.isAcceleratedLine())      // 청구중지 회차는 개별 연체로 보지 않는다
                    .filter(ps -> ps.getPaymentDate() != null && ps.getPaymentDate().isBefore(today))
                    .filter(ps -> ps.unpaidTotal() > 0)
                    .sorted(Comparator.comparing(PaymentSchedule::getPaymentDate))
                    .toList();
            if (!overdueLines.isEmpty()) out.put(c, overdueLines);
        }
        return out;
    }

    /**
     * 지연배상금 — 미납 원금에만 붙인다.
     *
     * <p>미납 이자에까지 붙이면 이자에 이자를 물리는 복리가 되어 이자제한법에 걸린다.
     * 회차마다 그 회차의 납입예정일부터 오늘까지로 계산해 더한다(변제충당과 같은 방식).
     */
    private long lateFeeOf(Contract c, List<PaymentSchedule> overdueLines, LocalDate today) {
        if (Boolean.FALSE.equals(c.getOverdueChargeYn())) return 0L;

        long sum = 0;
        for (PaymentSchedule ps : overdueLines) {
            long unpaidPrincipal = ps.unpaidPrincipal();
            if (unpaidPrincipal <= 0) continue;
            long accrued = DailyInterestCalculator.overdueInterest(
                    true, unpaidPrincipal, c.getOverdueRate(), ps.getPaymentDate(), today);
            sum += Math.max(0, accrued - nz(ps.getPaidOverdueInterest()));
        }
        return sum;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
