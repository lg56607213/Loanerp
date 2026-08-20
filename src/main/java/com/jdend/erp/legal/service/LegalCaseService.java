package com.jdend.erp.legal.service;

import com.jdend.erp.accounting.settings.service.OtherAccountSettingsService;
import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import com.jdend.erp.accounting.voucher.service.VoucherNumberService;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.legal.dto.*;
import com.jdend.erp.legal.entity.LegalCase;
import com.jdend.erp.legal.entity.LegalCostItem;
import com.jdend.erp.legal.entity.LegalProgressEntry;
import com.jdend.erp.legal.repository.LegalCaseRepository;
import com.jdend.erp.legal.repository.LegalCostItemRepository;
import com.jdend.erp.legal.repository.LegalProgressEntryRepository;
import com.jdend.erp.accounting.voucher.service.AccountResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;


@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class LegalCaseService {

    private final ContractRepository contractRepo;
    private final LegalCaseRepository caseRepo;
    private final LegalProgressEntryRepository progressRepo;
    private final LegalCostItemRepository costItemRepo;
    private final VoucherRepository voucherRepository;
    private final OtherAccountSettingsService accountSettings;
    private final AccountResolver accountResolver;
    private final VoucherNumberService voucherNumberService;

    public LegalCaseResponse create(LegalCaseRequest req) {
        String cn = safe(req.getContractNumber());
        if (cn.isBlank()) throw new RuntimeException("계약번호는 필수입니다.");

        Contract c = contractRepo.findWithCustomerByContractNumber(cn)
                .orElseThrow(() -> new RuntimeException("계약 없음: " + cn));

        String customerName = c.getCustomer() != null ? c.getCustomer().getCustomerName() : null;

        LegalCase lc = LegalCase.builder()
                .contractNumber(c.getContractNumber())
                .customerName(customerName)
                .caseType(safe(req.getCaseType()))
                .caseNumber(safe(req.getCaseNumber()))
                .registrationDate(req.getRegistrationDate() != null ? req.getRegistrationDate() : LocalDate.now())
                .legalCostPayment(req.getLegalCostPayment() != null ? req.getLegalCostPayment() : 0L)
                .legalCostRefund(req.getLegalCostRefund() != null ? req.getLegalCostRefund() : 0L)
                .status(req.getStatus() != null && !req.getStatus().isBlank() ? req.getStatus() : "접수")
                .build();

        LegalCase savedCase = caseRepo.save(lc);
        return toResponse(savedCase, List.of(), List.of());
    }

    @Transactional(readOnly = true)
    public List<LegalCaseResponse> listByContract(String contractNumber) {
        return caseRepo.findByContractNumberOrderByIdDesc(safe(contractNumber))
                .stream()
                .map(c -> toResponse(c,
                        progressRepo.findByLegalCaseIdOrderByProgressDateAscIdAsc(c.getId()),
                        costItemRepo.findByLegalCaseIdOrderByCostDateAscIdAsc(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LegalCaseResponse> search(String kw, String status) {
        String kwParam  = (kw     != null && !kw.isBlank())     ? "%" + kw.trim().toLowerCase()    + "%" : null;
        String stParam  = (status != null && !status.isBlank()) ? status.trim()                          : null;
        return caseRepo.search(kwParam, stParam).stream()
                .map(c -> toResponse(c,
                        progressRepo.findByLegalCaseIdOrderByProgressDateAscIdAsc(c.getId()),
                        costItemRepo.findByLegalCaseIdOrderByCostDateAscIdAsc(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public LegalCaseResponse getOne(Long id) {
        LegalCase c = findCase(id);
        return toResponse(c,
                progressRepo.findByLegalCaseIdOrderByProgressDateAscIdAsc(id),
                costItemRepo.findByLegalCaseIdOrderByCostDateAscIdAsc(id));
    }

    public LegalCaseResponse update(Long id, LegalCaseRequest req) {
        LegalCase c = findCase(id);
        if (req.getCaseType() != null)       c.setCaseType(req.getCaseType());
        if (req.getCaseNumber() != null)     c.setCaseNumber(req.getCaseNumber());
        if (req.getRegistrationDate() != null) c.setRegistrationDate(req.getRegistrationDate());
        if (req.getStatus() != null && !req.getStatus().isBlank()) c.setStatus(req.getStatus());

        LegalCase saved = caseRepo.save(c);
        return toResponse(saved,
                progressRepo.findByLegalCaseIdOrderByProgressDateAscIdAsc(id),
                costItemRepo.findByLegalCaseIdOrderByCostDateAscIdAsc(id));
    }

    public void delete(Long id) {
        if (!caseRepo.existsById(id)) throw new RuntimeException("사건 없음: " + id);
        progressRepo.deleteByLegalCaseId(id);

        // BUG-5차-01: 비용항목 전표를 voucherId로 직접 삭제 (크로스 케이스 오삭제 방지)
        List<LegalCostItem> items = costItemRepo.findByLegalCaseIdOrderByCostDateAscIdAsc(id);
        for (LegalCostItem item : items) {
            if (item.getVoucherId() != null) {
                voucherRepository.findById(item.getVoucherId()).ifPresent(voucherRepository::delete);
            } else if (item.getCostDate() != null && item.getAmount() != null) {
                // 구형 데이터 fallback
                String memoPrefix = "법적절차 " + item.getCostType();
                List<Voucher> vouchers = voucherRepository.findByVoucherDateAndAmountAndMemoPrefix(
                        item.getCostDate(), item.getAmount(), memoPrefix);
                if (!vouchers.isEmpty()) {
                    voucherRepository.delete(vouchers.get(0));
                }
            }
        }
        costItemRepo.deleteByLegalCaseId(id);
        caseRepo.deleteById(id);
    }

    public LegalCostItemResponse addCostItem(Long caseId, LegalCostItemRequest req) {
        if (!caseRepo.existsById(caseId)) throw new RuntimeException("사건 없음: " + caseId);
        String type = req.getCostType();
        if (type == null || type.isBlank()) throw new RuntimeException("비용유형은 필수입니다.");
        if (req.getAmount() == null || req.getAmount() <= 0) throw new RuntimeException("금액은 양수여야 합니다.");

        LegalCostItem item = LegalCostItem.builder()
                .legalCaseId(caseId)
                .costType(type)
                .amount(req.getAmount())
                .costDate(req.getCostDate() != null ? req.getCostDate() : LocalDate.now())
                .memo(req.getMemo())
                .build();

        LegalCostItem saved = costItemRepo.save(item);
        Long voucherId = createCostItemVoucher(saved, findCase(caseId), req.getCompanyAccount());
        if (voucherId != null) {
            saved.setVoucherId(voucherId);
            costItemRepo.save(saved);
        }
        return toCostItemResponse(saved);
    }

    public void deleteCostItem(Long caseId, Long itemId) {
        LegalCostItem item = costItemRepo.findById(itemId)
                .orElseThrow(() -> new RuntimeException("비용항목 없음: " + itemId));
        if (!item.getLegalCaseId().equals(caseId)) throw new RuntimeException("사건 불일치");

        // BUG-5차-01: voucherId로 직접 삭제 (크로스 케이스 오삭제 방지)
        if (item.getVoucherId() != null) {
            voucherRepository.findById(item.getVoucherId()).ifPresent(voucherRepository::delete);
        } else if (item.getCostDate() != null && item.getAmount() != null) {
            // 구형 데이터 fallback: 날짜+금액+유형으로 첫 건 삭제
            String memoPrefix = "법적절차 " + item.getCostType();
            List<Voucher> vouchers = voucherRepository.findByVoucherDateAndAmountAndMemoPrefix(
                    item.getCostDate(), item.getAmount(), memoPrefix);
            if (!vouchers.isEmpty()) {
                voucherRepository.delete(vouchers.get(0));
            }
        }

        costItemRepo.deleteById(itemId);
    }

    public LegalProgressResponse addProgress(Long caseId, LegalProgressRequest req) {
        if (!caseRepo.existsById(caseId)) throw new RuntimeException("사건 없음: " + caseId);
        String content = safe(req.getProgressContent());
        if (content.isBlank()) throw new RuntimeException("진행내역 내용은 필수입니다.");

        LegalProgressEntry entry = LegalProgressEntry.builder()
                .legalCaseId(caseId)
                .progressDate(req.getProgressDate() != null ? req.getProgressDate() : LocalDate.now())
                .progressContent(content)
                .build();

        return toProgressResponse(progressRepo.save(entry));
    }

    public void deleteProgress(Long caseId, Long entryId) {
        LegalProgressEntry e = progressRepo.findById(entryId)
                .orElseThrow(() -> new RuntimeException("진행내역 없음: " + entryId));
        if (!e.getLegalCaseId().equals(caseId)) throw new RuntimeException("사건 불일치");
        progressRepo.deleteById(entryId);
    }

    private LegalCase findCase(Long id) {
        return caseRepo.findById(id).orElseThrow(() -> new RuntimeException("사건 없음: " + id));
    }

    private Long createCostItemVoucher(LegalCostItem item, LegalCase lc, String companyAccount) {
        boolean isRefund = "환입".equals(item.getCostType());

        String debitAccount;
        String creditAccount;
        String debitDesc;
        String creditDesc;

        if (isRefund) {
            // 환입: DR 보통예금, CR 법무비용
            debitAccount  = accountSettings.getLegalCostCreditAccount(); // 보통예금/미지급금 계정
            creditAccount = accountSettings.getLegalCostDebitAccount();  // 법무비용 계정
            String acct = companyAccount != null && !companyAccount.isBlank() ? companyAccount.trim() : null;
            debitDesc  = acct != null ? "법적비용 환입 수취 [계좌: " + acct + "]" : "법적비용 환입 수취";
            creditDesc = "법적비용 환입 (" + item.getCostType() + ")";
        } else {
            // 신청비용/추가비용/확인비용: DR 법무비용, CR 미지급금
            debitAccount  = accountSettings.getLegalCostDebitAccount();
            creditAccount = accountSettings.getLegalCostCreditAccount();
            debitDesc  = "법적비용 (" + item.getCostType() + ")";
            creditDesc = "법적비용 지급 (" + item.getCostType() + ")";
        }

        if (debitAccount == null || creditAccount == null) {
            log.warn("법무비용 전표 생략: 기타계정관리 > 법적비용 전표의 차변/대변을 설정해주세요. legalCaseId={}", lc.getId());
            return null;
        }

        LocalDate voucherDate = item.getCostDate();
        String voucherNo = nextVoucherNo(voucherDate);

        String memoText = "법적절차 " + item.getCostType();
        if (lc.getCaseNumber() != null && !lc.getCaseNumber().isBlank()) memoText += " / 사건번호: " + lc.getCaseNumber();
        if (lc.getContractNumber() != null && !lc.getContractNumber().isBlank()) memoText += " / 계약번호: " + lc.getContractNumber();
        if (item.getMemo() != null && !item.getMemo().isBlank()) memoText += " / " + item.getMemo();

        Voucher voucher = Voucher.builder()
                .voucherNo(voucherNo)
                .voucherDate(voucherDate)
                .contractNumber(nullIfBlank(lc.getContractNumber()))
                .totalAmount(item.getAmount())
                .status("대기")
                .memo(memoText)
                .build();

        voucher.addLine(VoucherLine.builder()
                .lineType("DEBIT").accountCode(accountResolver.codeOf(debitAccount)).accountName(debitAccount).amount(item.getAmount())
                .description(debitDesc).sortOrder(1).build());
        voucher.addLine(VoucherLine.builder()
                .lineType("CREDIT").accountCode(accountResolver.codeOf(creditAccount)).accountName(creditAccount).amount(item.getAmount())
                .description(creditDesc).sortOrder(2).build());

        return voucherRepository.save(voucher).getId();
    }

    private String nextVoucherNo(LocalDate date) {
        return voucherNumberService.next(date);
    }

    private String nullIfBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private LegalCaseResponse toResponse(LegalCase c, List<LegalProgressEntry> entries, List<LegalCostItem> costItems) {
        return LegalCaseResponse.builder()
                .id(c.getId())
                .contractNumber(c.getContractNumber())
                .customerName(c.getCustomerName())
                .caseType(c.getCaseType())
                .caseNumber(c.getCaseNumber())
                .registrationDate(c.getRegistrationDate())
                .legalCostPayment(c.getLegalCostPayment())
                .legalCostRefund(c.getLegalCostRefund())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .progressEntries(entries.stream().map(this::toProgressResponse).toList())
                .costItems(costItems.stream().map(this::toCostItemResponse).toList())
                .build();
    }

    private LegalCostItemResponse toCostItemResponse(LegalCostItem item) {
        return LegalCostItemResponse.builder()
                .id(item.getId())
                .legalCaseId(item.getLegalCaseId())
                .costType(item.getCostType())
                .amount(item.getAmount())
                .costDate(item.getCostDate())
                .memo(item.getMemo())
                .voucherId(item.getVoucherId())
                .createdAt(item.getCreatedAt())
                .build();
    }

    private LegalProgressResponse toProgressResponse(LegalProgressEntry e) {
        return LegalProgressResponse.builder()
                .id(e.getId())
                .legalCaseId(e.getLegalCaseId())
                .progressDate(e.getProgressDate())
                .progressContent(e.getProgressContent())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private String safe(String s) { return s == null ? "" : s.trim(); }
}
