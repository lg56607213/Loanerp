package com.jdend.erp.accounting.voucher.service;

import com.jdend.erp.accounting.voucher.dto.*;
import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import com.jdend.erp.management.financial.repository.FinancialStatementAccountRepository;
import com.jdend.erp.payment.payment.entity.Payment;
import com.jdend.erp.payment.payment.repository.PaymentRepository;
import com.jdend.erp.payment.receivable.entity.Receivable;
import com.jdend.erp.payment.receivable.repository.ReceivableRepository;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherService {

    private final VoucherRepository voucherRepository;
    private final VoucherNumberService voucherNumberService;
    private final FinancialStatementAccountRepository financialAccountRepository;
    private final AccountResolver accountResolver;
    private final PaymentRepository paymentRepository;
    private final ReceivableRepository receivableRepository;
    private final PaymentScheduleRepository paymentScheduleRepository;

    @Transactional(readOnly = true)
    public String nextVoucherNo(LocalDate date) {
        String ymd = date.toString().replace("-", "");
        Long maxSeq = voucherRepository.findMaxSequenceForDatePrefix(ymd);
        long next = (maxSeq == null ? 0L : maxSeq) + 1;
        String voucherNo = ymd + String.format("%05d", next);
        while (voucherRepository.existsByVoucherNo(voucherNo)) {
            next++;
            voucherNo = ymd + String.format("%05d", next);
        }
        return voucherNo;
    }

    @Transactional
    public VoucherCreateResponse create(VoucherCreateRequest req) {
        if (req.getVoucherDate() == null) {
            throw new IllegalArgumentException("voucherDate(전표일자)는 필수입니다.");
        }
        // BUG-8차-04: 오늘로부터 30일 초과 미래 날짜는 입력 오류 방지 차원에서 차단
        if (req.getVoucherDate().isAfter(LocalDate.now().plusDays(30))) {
            throw new IllegalArgumentException("전표일자가 오늘로부터 30일 이상 미래입니다. 날짜를 확인해주세요.");
        }

        // BUG-9차-05: 차변/대변 유효성 검증을 채번(REQUIRES_NEW) 이전에 수행하여 시퀀스 갭 최소화.
        // VoucherNumberService.next()는 별도 트랜잭션으로 시퀀스를 선점·커밋하므로
        // 이후 롤백이 발생해도 번호는 소비된다. 검증을 먼저 완료해 불필요한 소비를 줄인다.
        // (설계상 갭 완전 제거는 불가, 중복 없음은 보장)
        long debitSum = sum(req, true);
        long creditSum = sum(req, false);

        if (debitSum <= 0 || creditSum <= 0) {
            throw new IllegalArgumentException("차변/대변 금액은 0보다 커야 합니다.");
        }
        if (debitSum != creditSum) {
            throw new IllegalArgumentException("차변과 대변 합계가 일치하지 않습니다. (차변=" + debitSum + ", 대변=" + creditSum + ")");
        }

        String voucherNo = (req.getVoucherNo() == null || req.getVoucherNo().isBlank())
                ? voucherNumberService.next(req.getVoucherDate())
                : req.getVoucherNo().trim();

        if (voucherRepository.existsByVoucherNo(voucherNo)) {
            throw new IllegalArgumentException("이미 존재하는 전표번호입니다: " + voucherNo);
        }

        Voucher voucher = Voucher.builder()
                .voucherNo(voucherNo)
                .voucherDate(req.getVoucherDate())
                .contractNumber(blankToNull(req.getContractNumber()))
                .totalAmount(debitSum)
                .status("대기")
                .memo(blankToNull(req.getMemo()))
                .build();

        int sort = 1;

        if (req.getDebitEntries() != null) {
            for (var lineReq : req.getDebitEntries()) {
                if (lineReq == null) continue;
                if (isBlank(lineReq.getAccountCode()) && isBlank(lineReq.getAccount())) continue;

                long amt = lineReq.getAmount() == null ? 0 : lineReq.getAmount();
                if (amt <= 0) continue;

                String[] resolved = resolveAccount(lineReq.getAccountCode(), lineReq.getAccount());
                voucher.addLine(VoucherLine.builder()
                        .lineType("DEBIT")
                        .accountCode(resolved[0])
                        .accountName(resolved[1])
                        .amount(amt)
                        .description(blankToNull(lineReq.getDescription()))
                        .sortOrder(sort++)
                        .build());
            }
        }

        if (req.getCreditEntries() != null) {
            for (var lineReq : req.getCreditEntries()) {
                if (lineReq == null) continue;
                if (isBlank(lineReq.getAccountCode()) && isBlank(lineReq.getAccount())) continue;

                long amt = lineReq.getAmount() == null ? 0 : lineReq.getAmount();
                if (amt <= 0) continue;

                String[] resolved = resolveAccount(lineReq.getAccountCode(), lineReq.getAccount());
                voucher.addLine(VoucherLine.builder()
                        .lineType("CREDIT")
                        .accountCode(resolved[0])
                        .accountName(resolved[1])
                        .amount(amt)
                        .description(blankToNull(lineReq.getDescription()))
                        .sortOrder(sort++)
                        .build());
            }
        }

        Voucher saved = voucherRepository.save(voucher);

        saved.getLines().forEach(line -> {
            if (line.getAccountCode() == null || line.getAccountCode().isBlank()) {
                log.warn("[전표경고] 계정코드 미확인 계정명 사용: '{}' (voucherNo={}, lineType={}). " +
                        "재무제표관리에서 계정을 먼저 등록하세요.",
                        line.getAccountName(), saved.getVoucherNo(), line.getLineType());
            }
        });

        return VoucherCreateResponse.builder()
                .id(saved.getId())
                .voucherNo(saved.getVoucherNo())
                .build();
    }

    /**
     * 전표승인 화면용
     * - 전표 1건 안의 debit/credit 라인을 각각 순서대로 매칭해서 여러 줄로 펼쳐줌
     * - 첫 줄만 체크박스/일자/전표번호/상태 표시
     */
    @Transactional(readOnly = true)
    public List<VoucherApprovalRowResponse> listForApproval(LocalDate startDate, LocalDate endDate, String status) {
        return voucherRepository.searchForApprovalRange(startDate, endDate, status).stream()
                .flatMap(v -> {
                    List<VoucherLine> lines = v.getLines().stream()
                            .sorted(Comparator.comparing(VoucherLine::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                            .toList();

                    List<VoucherLine> debitLines = lines.stream()
                            .filter(line -> "DEBIT".equalsIgnoreCase(line.getLineType()))
                            .toList();

                    List<VoucherLine> creditLines = lines.stream()
                            .filter(line -> "CREDIT".equalsIgnoreCase(line.getLineType()))
                            .toList();

                    int rowCount = Math.max(Math.max(debitLines.size(), creditLines.size()), 1);

                    return java.util.stream.IntStream.range(0, rowCount)
                            .mapToObj(i -> {
                                VoucherLine debit = i < debitLines.size() ? debitLines.get(i) : null;
                                VoucherLine credit = i < creditLines.size() ? creditLines.get(i) : null;

                                return VoucherApprovalRowResponse.builder()
                                        .id(v.getId())
                                        .voucherDate(v.getVoucherDate())
                                        .voucherNo(v.getVoucherNo())

                                        .debitAccount(debit != null ? nvl(debit.getAccountName()) : "")
                                        .debitAmount(debit != null ? debit.getAmount() : null)
                                        .debitDescription(debit != null ? nvl(debit.getDescription()) : "")

                                        .creditAccount(credit != null ? nvl(credit.getAccountName()) : "")
                                        .creditAmount(credit != null ? credit.getAmount() : null)
                                        .creditDescription(credit != null ? nvl(credit.getDescription()) : "")

                                        .status(v.getStatus())
                                        .contractNumber(v.getContractNumber())
                                        .showMain(i == 0)
                                        .build();
                            });
                })
                .toList();
    }

    @Transactional
    public int approveByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return voucherRepository.approveByIds(ids);
    }

    @Transactional
    public int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;

        // 수납과 연결된 전표면 미수금 복구 후 수납 레코드 삭제 (B안)
        List<Payment> linked = paymentRepository.findByVoucherIdIn(ids);
        for (Payment p : linked) {
            cancelPaymentReceivable(p.getContractNumber(), p.getPaymentAmount());
            // BUG-05 수정: 전표 삭제 시 연관 PaymentSchedule의 paymentDate도 null로 복구
            cancelPaymentSchedule(p.getContractNumber(), p.getPaymentAmount());
            paymentRepository.delete(p);
            log.info("전표 삭제로 수납 자동 취소·삭제: paymentId={}, contractNumber={}", p.getId(), p.getContractNumber());
        }

        voucherRepository.deleteAllById(ids);
        return ids.size();
    }

    /**
     * BUG-05 수정: 전표 삭제 시 연관 PaymentSchedule의 paymentDate를 null로 복구.
     * Payment에 installmentNo가 없으므로 가장 최근 납입 완료 스케줄부터 paymentAmount 기준으로 역순 복구.
     */
    private void cancelPaymentSchedule(String contractNumber, Long paymentAmount) {
        if (contractNumber == null || contractNumber.isBlank()) return;
        if (paymentAmount == null || paymentAmount <= 0) return;

        List<PaymentSchedule> all = paymentScheduleRepository
                .findByContractNumberOrderByInstallmentNoAsc(contractNumber);

        // 납입완료(paymentDate != null) 스케줄을 최신 회차부터 역순 처리
        List<PaymentSchedule> paid = all.stream()
                .filter(s -> s.getPaymentDate() != null)
                .sorted(Comparator.comparing(PaymentSchedule::getInstallmentNo).reversed())
                .toList();

        long remaining = paymentAmount;
        for (PaymentSchedule ps : paid) {
            if (remaining <= 0) break;
            long schedAmt = nvl(ps.getRentAmount()) + nvl(ps.getPrincipalAmount()) + nvl(ps.getInterestAmount());
            if (schedAmt <= 0) {
                // 금액 정보 없으면 해당 스케줄만 초기화하고 중단
                ps.setPaymentDate(null);
                paymentScheduleRepository.save(ps);
                break;
            }
            if (remaining >= schedAmt) {
                ps.setPaymentDate(null);
                paymentScheduleRepository.save(ps);
                remaining -= schedAmt;
            } else {
                break;
            }
        }
    }

    private void cancelPaymentReceivable(String contractNumber, Long paymentAmount) {
        if (contractNumber == null || contractNumber.isBlank()) return;
        if (paymentAmount == null || paymentAmount <= 0) return;

        List<Receivable> paid = receivableRepository.findByContractNumberAndStatusInOrderByIdDesc(
                contractNumber, java.util.List.of("완납", "완료"));
        long toReverse = paymentAmount;
        for (Receivable r : paid) {
            long amt = r.getReceivableAmount() == null ? 0L : r.getReceivableAmount();
            if (toReverse >= amt) {
                r.setStatus("미납");
                receivableRepository.save(r);
                toReverse -= amt;
            } else {
                break;
            }
        }
    }

    // [0]=accountCode (null 가능), [1]=accountName
    // 자동전표 생성 서비스들과 동일한 규칙을 쓰도록 AccountResolver 로 위임한다.
    private String[] resolveAccount(String code, String name) {
        AccountResolver.Resolved r = accountResolver.resolve(code, name);
        return new String[]{r.code(), r.name()};
    }

    private long sum(VoucherCreateRequest req, boolean debit) {
        long s = 0;
        var list = debit ? req.getDebitEntries() : req.getCreditEntries();
        if (list == null) return 0;

        for (var r : list) {
            if (r == null) continue;
            if (isBlank(r.getAccountCode()) && isBlank(r.getAccount())) continue;

            long amt = r.getAmount() == null ? 0 : r.getAmount();
            if (amt > 0) s += amt;
        }
        return s;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private long nvl(Long v) {
        return v == null ? 0L : v;
    }
}