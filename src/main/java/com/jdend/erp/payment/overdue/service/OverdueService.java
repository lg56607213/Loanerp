package com.jdend.erp.payment.overdue.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.payment.overdue.dto.OverdueRowResponse;
import com.jdend.erp.payment.payment.entity.Payment;
import com.jdend.erp.payment.payment.repository.PaymentRepository;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OverdueService {

    private final ContractRepository contractRepo;
    private final PaymentScheduleRepository scheduleRepo;
    private final PaymentRepository paymentRepo;

    /**
     * BUG-07: N+1 수정 — 계약별 개별 쿼리 → 배치 조회로 변경
     * (이전: 계약 수 N만큼 scheduleRepo + paymentRepo 개별 조회)
     * (이후: contractNumbers 목록으로 한 번에 조회 후 메모리 그루핑)
     */
    @Transactional(readOnly = true)
    public List<OverdueRowResponse> overdueList() {
        LocalDate today = LocalDate.now();
        List<Contract> activeContracts = contractRepo.findAllActiveWithCustomer();
        if (activeContracts.isEmpty()) return List.of();

        List<String> contractNumbers = activeContracts.stream()
                .map(Contract::getContractNumber)
                .filter(Objects::nonNull)
                .toList();

        // 배치 조회
        Map<String, List<PaymentSchedule>> schedulesByContract =
                scheduleRepo.findByContractNumberIn(contractNumbers).stream()
                        .collect(Collectors.groupingBy(PaymentSchedule::getContractNumber));

        Map<String, Long> totalPaidByContract =
                paymentRepo.findByContractNumberIn(contractNumbers).stream()
                        .collect(Collectors.groupingBy(
                                Payment::getContractNumber,
                                Collectors.summingLong(p -> p.getPaymentAmount() != null ? p.getPaymentAmount() : 0L)
                        ));

        List<OverdueRowResponse> result = new ArrayList<>();

        for (Contract c : activeContracts) {
            String contractNumber = c.getContractNumber();
            List<PaymentSchedule> schedules = schedulesByContract.getOrDefault(contractNumber, List.of());
            if (schedules.isEmpty()) continue;

            // installmentNo 오름차순 정렬 보장
            schedules = schedules.stream()
                    .sorted(Comparator.comparingInt(ps -> ps.getInstallmentNo() == null ? 0 : ps.getInstallmentNo()))
                    .toList();

            long remaining = totalPaidByContract.getOrDefault(contractNumber, 0L);
            String customerName = (c.getCustomer() != null) ? c.getCustomer().getCustomerName() : null;

            for (PaymentSchedule ps : schedules) {
                long rent = ps.getRentAmount() != null ? ps.getRentAmount() : 0L;
                long paid = Math.max(0, Math.min(remaining, rent));
                remaining = Math.max(0, remaining - paid);
                long unpaid = Math.max(0, rent - paid);

                LocalDate dueDate = ps.getPaymentDate();
                if (dueDate != null && dueDate.isBefore(today) && unpaid > 0) {
                    result.add(OverdueRowResponse.builder()
                            .contractNumber(contractNumber)
                            .customerName(customerName)
                            .installmentNo(ps.getInstallmentNo())
                            .paymentDate(dueDate)
                            .rentAmount(rent)
                            .paidAmount(paid)
                            .unpaidAmount(unpaid)
                            .overdueDays((int) ChronoUnit.DAYS.between(dueDate, today))
                            .build());
                }
            }
        }
        return result;
    }

    /**
     * BUG-07: N+1 수정 + 로직 일치 (overdueList()와 동일한 remaining 차감 방식 사용)
     */
    @Transactional(readOnly = true)
    public Set<String> overdueContractNumbers() {
        LocalDate today = LocalDate.now();
        List<Contract> activeContracts = contractRepo.findAllActiveWithCustomer();
        if (activeContracts.isEmpty()) return Set.of();

        List<String> contractNumbers = activeContracts.stream()
                .map(Contract::getContractNumber)
                .filter(Objects::nonNull)
                .toList();

        // 배치 조회
        Map<String, List<PaymentSchedule>> schedulesByContract =
                scheduleRepo.findByContractNumberIn(contractNumbers).stream()
                        .collect(Collectors.groupingBy(PaymentSchedule::getContractNumber));

        Map<String, Long> totalPaidByContract =
                paymentRepo.findByContractNumberIn(contractNumbers).stream()
                        .collect(Collectors.groupingBy(
                                Payment::getContractNumber,
                                Collectors.summingLong(p -> p.getPaymentAmount() != null ? p.getPaymentAmount() : 0L)
                        ));

        Set<String> result = new HashSet<>();

        for (Contract c : activeContracts) {
            String contractNumber = c.getContractNumber();
            List<PaymentSchedule> schedules = schedulesByContract.getOrDefault(contractNumber, List.of());
            if (schedules.isEmpty()) continue;

            schedules = schedules.stream()
                    .sorted(Comparator.comparingInt(ps -> ps.getInstallmentNo() == null ? 0 : ps.getInstallmentNo()))
                    .toList();

            // BUG-07 로직 일치: overdueList()와 동일한 remaining 차감 방식
            long remaining = totalPaidByContract.getOrDefault(contractNumber, 0L);

            for (PaymentSchedule ps : schedules) {
                long rent = ps.getRentAmount() != null ? ps.getRentAmount() : 0L;
                long paid = Math.max(0, Math.min(remaining, rent));
                remaining = Math.max(0, remaining - paid);
                long unpaid = Math.max(0, rent - paid);

                LocalDate dueDate = ps.getPaymentDate();
                if (dueDate != null && dueDate.isBefore(today) && unpaid > 0) {
                    result.add(contractNumber);
                    break;
                }
            }
        }
        return result;
    }
}
