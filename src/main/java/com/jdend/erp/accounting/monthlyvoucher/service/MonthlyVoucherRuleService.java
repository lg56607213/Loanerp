package com.jdend.erp.accounting.monthlyvoucher.service;

import com.jdend.erp.accounting.monthlyvoucher.dto.MonthlyVoucherRuleCreateRequest;
import com.jdend.erp.accounting.monthlyvoucher.dto.MonthlyVoucherRuleCreateResponse;
import com.jdend.erp.accounting.monthlyvoucher.dto.MonthlyVoucherRuleListResponse;
import com.jdend.erp.accounting.monthlyvoucher.repository.MonthlyVoucherRuleRepository;

// ✅ 기존 엔티티 사용
import com.jdend.erp.accounting.voucher.entity.MonthlyVoucherRule;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MonthlyVoucherRuleService {

  private final MonthlyVoucherRuleRepository ruleRepo;

  @Transactional
  public MonthlyVoucherRuleCreateResponse create(MonthlyVoucherRuleCreateRequest req) {

    if (req.getMonthlyDate() == null || req.getMonthlyDate() < 1 || req.getMonthlyDate() > 31) {
      throw new IllegalArgumentException("monthlyDate는 1~31 사이여야 합니다.");
    }
    if (req.getDebitAccount() == null || req.getDebitAccount().isBlank()) {
      throw new IllegalArgumentException("차변 계정명은 필수입니다.");
    }
    if (req.getCreditAccount() == null || req.getCreditAccount().isBlank()) {
      throw new IllegalArgumentException("대변 계정명은 필수입니다.");
    }

    long debit = req.getDebitAmount() == null ? 0 : req.getDebitAmount();
    long credit = req.getCreditAmount() == null ? 0 : req.getCreditAmount();

    if (debit <= 0 || credit <= 0) {
      throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
    }
    if (debit != credit) {
      throw new IllegalArgumentException("차변/대변 금액이 일치하지 않습니다.");
    }


    LocalDate today = LocalDate.now();
    LocalDate nextRun = calcNextRunDate(today, req.getMonthlyDate());

    // 계정코드 우선, 없으면 이름 그대로 저장 (VoucherService에서 실행 시점에 재확인)
    String debitCode = blankToNull(req.getDebitAccountCode());
    String creditCode = blankToNull(req.getCreditAccountCode());

    MonthlyVoucherRule rule = MonthlyVoucherRule.builder()
        .active(true)
        .contractNumber(blankToNull(req.getContractNumber()))
        .monthlyDay(req.getMonthlyDate())
        .nextRunDate(nextRun)
        .lastRunDate(null)
        .debitAccountCode(debitCode)
        .debitAccount(req.getDebitAccount().trim())
        .debitAmount(debit)
        .debitDescription(blankToNull(req.getDebitDescription()))
        .creditAccountCode(creditCode)
        .creditAccount(req.getCreditAccount().trim())
        .creditAmount(credit)
        .creditDescription(blankToNull(req.getCreditDescription()))
        .memo(blankToNull(req.getMemo()))
        .build();

    MonthlyVoucherRule saved = ruleRepo.save(rule);

    // ✅ boolean getter는 Lombok이 isActive()로 만들어줌
    return MonthlyVoucherRuleCreateResponse.builder()
        .id(saved.getId())
        .isActive(saved.isActive())
        .contractNumber(saved.getContractNumber())
        .monthlyDay(saved.getMonthlyDay())
        .nextRunDate(saved.getNextRunDate())
        .debitAccount(saved.getDebitAccount())
        .debitAmount(saved.getDebitAmount())
        .creditAccount(saved.getCreditAccount())
        .creditAmount(saved.getCreditAmount())
        .build();
  }

  private LocalDate calcNextRunDate(LocalDate base, int day) {
    LocalDate thisMonth = clampDay(base.withDayOfMonth(1), day);
    if (!thisMonth.isBefore(base)) return thisMonth; // 오늘 포함

    LocalDate nextMonthFirst = base.plusMonths(1).withDayOfMonth(1);
    return clampDay(nextMonthFirst, day);
  }

  private LocalDate clampDay(LocalDate monthFirst, int day) {
    int last = monthFirst.lengthOfMonth();
    return monthFirst.withDayOfMonth(Math.min(day, last));
  }

  public List<MonthlyVoucherRuleListResponse> list(Boolean activeOnly, String contractNumber) {
    String cn = (contractNumber == null || contractNumber.isBlank()) ? null : contractNumber.trim();
    return ruleRepo.search(activeOnly, cn)
        .stream()
        .map(MonthlyVoucherRuleListResponse::from)
        .toList();
  }

  @Transactional
  public void delete(Long id) {
    MonthlyVoucherRule rule = ruleRepo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("월전표 규칙을 찾을 수 없습니다: " + id));
    ruleRepo.delete(rule);
  }

  @Transactional
  public MonthlyVoucherRuleListResponse toggleActive(Long id) {
    MonthlyVoucherRule rule = ruleRepo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("월전표 규칙을 찾을 수 없습니다: " + id));
    rule.setActive(!rule.isActive());
    return MonthlyVoucherRuleListResponse.from(ruleRepo.save(rule));
  }

  private String blankToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}