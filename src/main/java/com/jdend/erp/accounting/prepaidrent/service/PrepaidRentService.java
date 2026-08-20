package com.jdend.erp.accounting.prepaidrent.service;

import com.jdend.erp.accounting.prepaidrent.dto.PrepaidRentCreateRequest;
import com.jdend.erp.accounting.prepaidrent.dto.PrepaidRentHistoryResponse;
import com.jdend.erp.accounting.prepaidrent.dto.PrepaidRentListItemResponse;
import com.jdend.erp.accounting.prepaidrent.entity.PrepaidRent;
import com.jdend.erp.accounting.prepaidrent.repository.PrepaidRentRepository;
import com.jdend.erp.accounting.settings.service.OtherAccountSettingsService;
import com.jdend.erp.accounting.voucher.dto.VoucherCreateRequest;
import com.jdend.erp.accounting.voucher.service.VoucherService;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrepaidRentService {

    private final PrepaidRentRepository prepaidRepo;
    private final ContractRepository contractRepo;
    private final VoucherService voucherService;
    private final OtherAccountSettingsService settingsService;
    private final PaymentScheduleRepository paymentSchedulesRepo;

    // ─────────────────────────────────────────────────────────────────────
    // 목록 조회
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PrepaidRentListItemResponse> list(LocalDate startDate, LocalDate endDate, String customerName) {
        // 1. 선수금 거래가 있는 계약 ID 조회
        List<Long> contractIds = prepaidRepo.findDistinctContractIds();
        if (contractIds.isEmpty()) return Collections.emptyList();

        // 2. 계약 정보 조회 (고객명·기간 필터 포함)
        String custNameParam = (customerName == null) ? "" : customerName.trim();
        List<Contract> contracts = contractRepo.findByIdsWithCustomerAndFilter(
                contractIds, custNameParam, startDate, endDate);
        if (contracts.isEmpty()) return Collections.emptyList();

        // 3. 필터된 계약 ID로 집계 쿼리 실행
        List<Long> filteredIds = contracts.stream().map(Contract::getId).toList();

        // [contractId|transactionType → sum]
        Map<String, Long> sumMap = new HashMap<>();
        for (Object[] row : prepaidRepo.sumByContractIds(filteredIds)) {
            Long cid = toLong(row[0]);
            String tt = (String) row[1];
            sumMap.put(cid + "|" + tt, toLong(row[2]));
        }

        // [contractId → 최근입금일]
        Map<Long, LocalDate> lastDepositMap = new HashMap<>();
        for (Object[] row : prepaidRepo.lastDepositDateByContractIds(filteredIds)) {
            lastDepositMap.put(toLong(row[0]), (LocalDate) row[1]);
        }

        // 4. 응답 생성
        List<PrepaidRentListItemResponse> result = new ArrayList<>();
        for (Contract c : contracts) {
            String custName = c.getCustomer() != null ? c.getCustomer().getCustomerName() : "";
            long deposited = Optional.ofNullable(sumMap.get(c.getId() + "|입금")).orElse(0L);
            long applied   = Optional.ofNullable(sumMap.get(c.getId() + "|적용")).orElse(0L);
            long balance = deposited - applied;

            // 잔액이 없는 계약은 목록에서 제외
            if (balance <= 0) continue;

            result.add(PrepaidRentListItemResponse.builder()
                    .contractId(c.getId())
                    .contractNumber(c.getContractNumber())
                    .customerName(custName)
                    .monthlyRent(c.getMonthlyPayment())
                    .balance(balance)
                    .lastDepositDate(lastDepositMap.get(c.getId()))
                    .build());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 거래 이력 조회
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PrepaidRentHistoryResponse> history(Long contractId) {
        List<PrepaidRent> txList =
                prepaidRepo.findByContractIdOrderByTransactionDateAscIdAsc(contractId);

        List<PrepaidRentHistoryResponse> result = new ArrayList<>();
        long cumulative = 0L;

        for (PrepaidRent p : txList) {
            if ("입금".equals(p.getTransactionType())) {
                cumulative += p.getAmount();
            } else {
                cumulative -= p.getAmount();
            }
            result.add(PrepaidRentHistoryResponse.builder()
                    .id(p.getId())
                    .transactionDate(p.getTransactionDate())
                    .transactionType(p.getTransactionType())
                    .amount(p.getAmount())
                    .cumulativeBalance(cumulative)
                    .memo(p.getMemo())
                    .voucherCreated(p.getVoucherCreated())
                    .build());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 선수금 입금 등록
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public void deposit(PrepaidRentCreateRequest req) {
        validateRequest(req);

        PrepaidRent p = PrepaidRent.builder()
                .contractId(req.getContractId())
                .transactionType("입금")
                .amount(req.getAmount())
                .transactionDate(req.getTransactionDate())
                .memo(req.getMemo())
                .voucherCreated(Boolean.TRUE.equals(req.getCreateVoucher()))
                .build();
        prepaidRepo.save(p);

        if (Boolean.TRUE.equals(req.getCreateVoucher())) {
            createDepositVoucher(req);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 선수금 적용(지급처리) 등록
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public void apply(PrepaidRentCreateRequest req) {
        validateRequest(req);

        Contract contract = findContract(req.getContractId());

        // 연체된 스케줄이 있을 때만 수납 허용 (납기일이 어제 이전이고 미납인 것)
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<PaymentSchedule> overdueSchedules = paymentSchedulesRepo
                .findUnpaidByContractNumberAndDateLTE(contract.getContractNumber(), yesterday);
        if (overdueSchedules.isEmpty()) {
            throw new IllegalArgumentException("연체된 렌트료가 없어 선수금 수납이 불가능합니다.");
        }

        // 잔액 초과 적용 방지
        List<Long> ids = List.of(req.getContractId());
        Map<String, Long> sumMap = new HashMap<>();
        for (Object[] row : prepaidRepo.sumByContractIds(ids)) {
            sumMap.put(toLong(row[0]) + "|" + row[1], toLong(row[2]));
        }
        long deposited = Optional.ofNullable(sumMap.get(req.getContractId() + "|입금")).orElse(0L);
        long applied   = Optional.ofNullable(sumMap.get(req.getContractId() + "|적용")).orElse(0L);
        long balance   = deposited - applied;

        if (req.getAmount() > balance) {
            throw new IllegalArgumentException(
                    "적용 금액(" + fmt(req.getAmount()) + "원)이 선수금 잔액(" + fmt(balance) + "원)을 초과합니다.");
        }

        PrepaidRent p = PrepaidRent.builder()
                .contractId(req.getContractId())
                .transactionType("적용")
                .amount(req.getAmount())
                .transactionDate(req.getTransactionDate())
                .memo(req.getMemo())
                .voucherCreated(Boolean.TRUE.equals(req.getCreateVoucher()))
                .build();
        prepaidRepo.save(p);

        if (Boolean.TRUE.equals(req.getCreateVoucher())) {
            createApplyVoucher(req);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 전표 생성 헬퍼
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 선수금 입금 전표
     *   차변: 보통예금  (getPrepaidBankAccount)
     *   대변: 선수금    (getPrepaidDebitAccount)
     */
    private void createDepositVoucher(PrepaidRentCreateRequest req) {
        // 기타계정관리 > 선수금 전표 설정의 계정코드를 사용한다 (미설정 시 기본코드).
        String bankCode    = settingsService.getPrepaidBankAccountCode();
        String prepaidCode = settingsService.getPrepaidDebitAccountCode();

        Contract contract = findContract(req.getContractId());
        String memoText = "선수금 입금" + appendMemo(req.getMemo());

        String debitDesc = memoText;
        if (req.getCompanyAccount() != null && !req.getCompanyAccount().isBlank()) {
            debitDesc += " [계좌: " + req.getCompanyAccount() + "]";
        }

        voucherService.create(VoucherCreateRequest.builder()
                .voucherDate(req.getTransactionDate())
                .contractNumber(contract.getContractNumber())
                .memo(memoText)
                .debitEntries(List.of(VoucherCreateRequest.VoucherLineRequest.builder()
                        .accountCode(bankCode)
                        .amount(req.getAmount())
                        .description(debitDesc)
                        .build()))
                .creditEntries(List.of(VoucherCreateRequest.VoucherLineRequest.builder()
                        .accountCode(prepaidCode)
                        .amount(req.getAmount())
                        .description(memoText)
                        .build()))
                .build());
    }

    /**
     * 선수금 적용 전표
     *   차변: 선수금    (getPrepaidDebitAccount)
     *   대변: 임대수익  (getPrepaidCreditAccount)
     */
    private void createApplyVoucher(PrepaidRentCreateRequest req) {
        // 기타계정관리 > 선수금 전표 설정의 계정코드를 사용한다 (미설정 시 기본코드).
        String prepaidCode = settingsService.getPrepaidDebitAccountCode();
        String revenueCode = settingsService.getPrepaidCreditAccountCode();
        Contract contract = findContract(req.getContractId());
        String memoText = "선수금 적용" + appendMemo(req.getMemo());
        long amount = req.getAmount();

        // 면세사업(대부업)이므로 부가세 분리 없이 총액으로 계상한다.
        List<VoucherCreateRequest.VoucherLineRequest> creditEntries = new ArrayList<>();
        creditEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
                .accountCode(revenueCode).amount(amount).description(memoText).build());

        voucherService.create(VoucherCreateRequest.builder()
                .voucherDate(req.getTransactionDate())
                .contractNumber(contract.getContractNumber())
                .memo(memoText)
                .debitEntries(List.of(VoucherCreateRequest.VoucherLineRequest.builder()
                        .accountCode(prepaidCode)
                        .amount(amount)
                        .description(memoText)
                        .build()))
                .creditEntries(creditEntries)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 납부 스케줄 조회 — 선수금 수납 화면용
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSchedules(Long contractId) {
        Contract contract = findContract(contractId);
        List<PaymentSchedule> schedules = paymentSchedulesRepo
                .findAllByContractNumberOrdered(contract.getContractNumber());

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> result = new ArrayList<>();
        for (PaymentSchedule ps : schedules) {
            boolean isPaid    = ps.getPaymentDate() != null;
            boolean isOverdue = !isPaid && ps.getTaxInvoiceDate() != null
                                && ps.getTaxInvoiceDate().isBefore(today);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",             ps.getId());
            row.put("installmentNo",  ps.getInstallmentNo());
            row.put("taxInvoiceDate", ps.getTaxInvoiceDate());
            row.put("rentAmount",     ps.getRentAmount());
            row.put("paymentDate",    ps.getPaymentDate());
            row.put("isPaid",         isPaid);
            row.put("isOverdue",      isOverdue);
            result.add(row);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 수납 초과금액 자동 등록 / 정리 (PaymentService에서 호출)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 수납 초과금액을 선수금으로 자동 등록.
     * 전표는 수납 전표에 이미 포함되어 있으므로 별도 생성하지 않는다.
     */
    @Transactional
    public void registerFromPaymentExcess(Long contractId, Long amount,
                                          LocalDate transactionDate, String originalMemo, Long paymentId) {
        if (contractId == null || amount == null || amount <= 0) return;
        String tag  = "[자동수납:" + paymentId + "]";
        String memo = (originalMemo != null && !originalMemo.isBlank()) ? tag + " " + originalMemo : tag;
        PrepaidRent p = PrepaidRent.builder()
                .contractId(contractId)
                .transactionType("입금")
                .amount(amount)
                .transactionDate(transactionDate)
                .memo(memo)
                .voucherCreated(true)
                .build();
        prepaidRepo.save(p);
        log.info("[선수금자동] paymentId={} contractId={} 초과금액={} 선수금 등록", paymentId, contractId, amount);
    }

    /** 수납 취소 시 연결된 자동 선수금 레코드 삭제 */
    @Transactional
    public void deleteByPaymentReference(Long contractId, Long paymentId) {
        if (contractId == null || paymentId == null) return;
        String tag = "[자동수납:" + paymentId + "]";
        prepaidRepo.findByContractIdOrderByTransactionDateAscIdAsc(contractId).stream()
                .filter(p -> p.getMemo() != null && p.getMemo().startsWith(tag))
                .forEach(p -> {
                    prepaidRepo.delete(p);
                    log.info("[선수금자동] paymentId={} 취소로 선수금 레코드 삭제 id={}", paymentId, p.getId());
                });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 내부 유틸
    // ─────────────────────────────────────────────────────────────────────

    private void validateRequest(PrepaidRentCreateRequest req) {
        if (req.getContractId() == null)
            throw new IllegalArgumentException("계약 ID는 필수입니다.");
        if (req.getAmount() == null || req.getAmount() <= 0)
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        if (req.getTransactionDate() == null)
            throw new IllegalArgumentException("거래일자는 필수입니다.");
    }

    private Contract findContract(Long contractId) {
        return contractRepo.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("계약을 찾을 수 없습니다: " + contractId));
    }

    private Long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }

    private String appendMemo(String memo) {
        return (memo != null && !memo.isBlank()) ? " - " + memo : "";
    }

    private String fmt(long amount) {
        return String.format("%,d", amount);
    }
}
