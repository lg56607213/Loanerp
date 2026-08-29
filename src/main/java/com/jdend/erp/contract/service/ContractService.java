package com.jdend.erp.contract.service;

import com.jdend.erp.contract.dto.*;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.entity.RepaymentMethod;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.contract.support.AmortizationCalculator;
import com.jdend.erp.contract.support.DebtTypeCode;
import com.jdend.erp.contract.support.LoanRateValidator;
import com.jdend.erp.accounting.settings.service.OtherAccountSettingsService;
import com.jdend.erp.accounting.voucher.dto.VoucherCreateRequest;
import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import com.jdend.erp.accounting.voucher.service.AccountResolver;
import com.jdend.erp.accounting.voucher.service.VoucherService;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.customer.CustomerRepository;
import com.jdend.erp.loan.support.LoanReceivableAccount;
import com.jdend.erp.payment.schedule.service.PaymentScheduleAutoGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 여신계약(대출채권) 등록·수정·조회 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

  public static final String LOAN_TYPE_CREDIT   = "신용대출";
  public static final String LOAN_TYPE_SECURED  = "담보대출";
  public static final String LOAN_TYPE_BUSINESS = "사업자대출";

  private final ContractRepository contractRepo;
  private final CustomerRepository customerRepo;
  private final PaymentScheduleAutoGeneratorService scheduleAutoGen;
  private final VoucherService voucherService;
  private final VoucherRepository voucherRepository;
  private final OtherAccountSettingsService accountSettings;
  private final AccountResolver accountResolver;

  /** 대출 실행 전표를 이 채권에 딸린 것으로 찾기 위한 적요 접두어 */
  private static final String EXEC_VOUCHER_MEMO = "대출실행";
  /** 자금이 나가는 계좌 기본값 — 기타계정관리에 설정이 없을 때 */
  private static final String DEFAULT_BANK_CODE = "100101";
  private static final String DEFAULT_BANK_NAME = "보통예금";

  @Transactional(readOnly = true)
  public List<ContractResponse> list() {
    List<Contract> list = contractRepo.findAll();
    list.sort(Comparator.comparing(Contract::getId).reversed());
    return list.stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public ContractResponse detail(Long id) {
    return toResponse(findById(id));
  }

  @Transactional(readOnly = true)
  public ContractFullResponse detailFull(Long id) {
    return toFullResponse(findById(id));
  }

  @Transactional(readOnly = true)
  public ContractFullResponse detailFullByNumber(String contractNumber) {
    if (isBlank(contractNumber)) {
      throw new RuntimeException("채권번호(contractNumber) 필수");
    }
    Contract c = contractRepo.findWithCustomerByContractNumber(contractNumber.trim())
        .orElseThrow(() -> new RuntimeException("채권 없음 contractNumber=" + contractNumber));
    return toFullResponse(c);
  }

  // ── 채권번호 채번 ─────────────────────────────────────────────

  /** 채권번호 미리보기. loanType 미지정 시 신용대출 기준으로 채번한다. */
  public String nextNumberPreview(String loanType) {
    return generateNextContractNumber(loanType != null ? loanType : LOAN_TYPE_CREDIT);
  }

  /**
   * 신규 채권번호 채번.
   *   신용대출 CRD / 담보대출 SEC / 사업자대출 BIZ
   *   + YYMMDD(6) + 순번(3) + 회차(3) = 15자리
   * 신규 채권의 회차는 항상 001이며, 만기연장·재약정만 회차를 올린다.
   */
  public synchronized String generateNextContractNumber(String loanType) {
    String prefix = switch (loanType == null ? "" : loanType) {
      case LOAN_TYPE_SECURED  -> "SEC";
      case LOAN_TYPE_BUSINESS -> "BIZ";
      default                 -> "CRD";
    };
    String yymmdd = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
    String datePrefix = prefix + yymmdd;

    Optional<String> maxOpt = contractRepo.findMaxContractNumberByPrefix(datePrefix);

    int nextSeq = 1;
    if (maxOpt.isPresent()) {
      try {
        nextSeq = Integer.parseInt(maxOpt.get().substring(9, 12)) + 1;
      } catch (Exception ignored) {}
    }
    return String.format("%s%03d001", datePrefix, nextSeq);
  }

  /**
   * 만기연장·재약정 채권번호 채번. 기존 번호의 base(앞 12자리)를 유지하고 회차만 올린다.
   * 예) CRD260804001001 → CRD260804001002
   */
  public synchronized String generateRerentContractNumber(String existingContractNo) {
    if (existingContractNo == null || existingContractNo.length() < 12) {
      throw new IllegalArgumentException("기존 채권번호가 올바르지 않습니다: " + existingContractNo);
    }
    String base = existingContractNo.substring(0, 12);
    Optional<String> maxOpt = contractRepo.findMaxContractNumberByPrefix(base);

    int nextRound = 1;
    if (maxOpt.isPresent()) {
      try {
        nextRound = Integer.parseInt(maxOpt.get().substring(12, 15)) + 1;
      } catch (Exception ignored) {}
    }
    return String.format("%s%03d", base, nextRound);
  }

  // ── 등록 / 수정 / 삭제 ───────────────────────────────────────

  @Transactional
  public ContractResponse create(ContractRequest req) {
    validateRequired(req);

    Customer customer = customerRepo.findByCustomerNumber(req.customerNumber).orElse(null);

    String method = normalizeRepaymentMethod(req.repaymentMethod);
    int installments = resolveInstallmentCount(req.installmentCount, req.startDate, req.endDate);
    long monthlyPayment = resolveMonthlyPayment(
        req.monthlyPayment, method, req.loanAmount, req.interestRate, installments);

    Contract c = Contract.builder()
        .contractNumber(generateNextContractNumber(req.loanType))
        .customer(customer)
        .customerNumber(req.customerNumber)
        .customerType(req.customerType)
        .loanType(req.loanType)
        .debtType(DebtTypeCode.normalize(req.debtType) != null
            ? DebtTypeCode.normalize(req.debtType)
            : DebtTypeCode.defaultFor(req.customerType, req.loanAmount))
        .loanAmount(nvl(req.loanAmount))
        .executeDate(req.executeDate != null ? req.executeDate : req.startDate)
        .interestRate(req.interestRate)
        .overdueRate(req.overdueRate)
        .overdueChargeYn(req.overdueChargeYn == null ? Boolean.TRUE : req.overdueChargeYn)
        .repaymentMethod(method)
        .startDate(req.startDate)
        .endDate(req.endDate)
        .paymentDay(req.paymentDay)
        .installmentCount(installments)
        .monthlyPayment(monthlyPayment)
        .status(normalizeStatus(req.status))
        .remainingPrincipal(nvl(req.loanAmount))
        .remarks(req.remarks)
        .build();

    contractRepo.save(c);
    scheduleAutoGen.ensureGenerated(c);
    createExecutionVoucher(c);
    return toResponse(c);
  }

  @Transactional
  public ContractResponse update(Long id, ContractUpdateRequest req) {
    Contract c = findById(id);

    boolean scheduleAffected = false;

    if (isNotBlank(req.getCustomerNumber()) && !req.getCustomerNumber().equals(c.getCustomerNumber())) {
      c.setCustomerNumber(req.getCustomerNumber());
      c.setCustomer(customerRepo.findByCustomerNumber(req.getCustomerNumber()).orElse(null));
    }
    if (isNotBlank(req.getCustomerType())) c.setCustomerType(req.getCustomerType());
    if (isNotBlank(req.getLoanType()))     c.setLoanType(req.getLoanType());
    if (isNotBlank(req.getDebtType()))     c.setDebtType(DebtTypeCode.normalize(req.getDebtType()));
    if (req.getExecuteDate() != null)      c.setExecuteDate(req.getExecuteDate());
    if (req.getRemarks() != null)          c.setRemarks(req.getRemarks());

    if (req.getLoanAmount() != null && !req.getLoanAmount().equals(c.getLoanAmount())) {
      c.setLoanAmount(req.getLoanAmount());
      scheduleAffected = true;
    }
    if (req.getInterestRate() != null && !safeEq(req.getInterestRate(), c.getInterestRate())) {
      c.setInterestRate(req.getInterestRate());
      scheduleAffected = true;
    }
    if (req.getOverdueRate() != null)     c.setOverdueRate(req.getOverdueRate());
    if (req.getOverdueChargeYn() != null) c.setOverdueChargeYn(req.getOverdueChargeYn());

    if (isNotBlank(req.getRepaymentMethod())
        && !req.getRepaymentMethod().equals(c.getRepaymentMethod())) {
      c.setRepaymentMethod(normalizeRepaymentMethod(req.getRepaymentMethod()));
      scheduleAffected = true;
    }
    if (req.getStartDate() != null && !req.getStartDate().equals(c.getStartDate())) {
      c.setStartDate(req.getStartDate());
      scheduleAffected = true;
    }
    if (req.getEndDate() != null && !req.getEndDate().equals(c.getEndDate())) {
      c.setEndDate(req.getEndDate());
      scheduleAffected = true;
    }
    if (req.getPaymentDay() != null && !req.getPaymentDay().equals(c.getPaymentDay())) {
      c.setPaymentDay(req.getPaymentDay());
      scheduleAffected = true;
    }
    if (req.getInstallmentCount() != null && !req.getInstallmentCount().equals(c.getInstallmentCount())) {
      c.setInstallmentCount(req.getInstallmentCount());
      scheduleAffected = true;
    }

    if (c.getEndDate() != null && c.getStartDate() != null && c.getEndDate().isBefore(c.getStartDate())) {
      throw new IllegalArgumentException("종료일자는 시작일자보다 이전일 수 없습니다.");
    }
    LoanRateValidator.validateInterestRate(c.getInterestRate());
    LoanRateValidator.validateOverdueRate(c.getOverdueRate(), c.getInterestRate());

    // 월납입액은 명시 입력이 있으면 그것을 쓰고, 조건이 바뀌었으면 다시 산출한다.
    if (req.getMonthlyPayment() != null) {
      c.setMonthlyPayment(req.getMonthlyPayment());
    } else if (scheduleAffected) {
      c.setMonthlyPayment(AmortizationCalculator.monthlyPayment(
          c.getRepaymentMethod(), nvl(c.getLoanAmount()), c.getInterestRate(), nvl(c.getInstallmentCount())));
    }

    contractRepo.save(c);

    if (scheduleAffected) {
      scheduleAutoGen.regenerate(c);
    } else {
      scheduleAutoGen.ensureGenerated(c);
    }

    // 대출금·기간·실행일이 바뀌면 실행 분개의 금액이나 계정(단기/장기)이 달라진다.
    // 증분으로 고치면 어긋나기 쉬워 지우고 다시 만든다.
    deleteExecutionVoucher(c.getContractNumber());
    createExecutionVoucher(c);

    return toResponse(c);
  }

  @Transactional
  public void delete(Long id) {
    Contract c = contractRepo.findById(id)
        .orElseThrow(() -> new RuntimeException("채권 없음 id=" + id));
    deleteExecutionVoucher(c.getContractNumber());
    contractRepo.deleteById(id);
  }

  // ── 대출 실행 전표 ───────────────────────────────────────────

  /**
   * 대출 실행 분개.
   *   (차) 단기대여금 또는 장기대여금 [대출금]  /  (대) 보통예금 [대출금]
   *
   * 어느 대여금 계정을 쓸지는 대출기간으로 정한다 — 1년 미만이면 단기, 1년 이상이면 장기.
   * 판정은 {@link LoanReceivableAccount} 한 곳에 모아 두었다. 수납(원금 회수)·대손상각도
   * 같은 규칙을 써야 계정별 잔액이 어긋나지 않는다.
   *
   * 전표가 실패해도 채권 등록 자체는 살린다. 등록이 통째로 막히는 편이 더 나쁘다.
   */
  private void createExecutionVoucher(Contract c) {
    try {
      long amount = nvl(c.getLoanAmount());
      if (amount <= 0) return;

      LocalDate date = c.getExecuteDate() != null ? c.getExecuteDate() : c.getStartDate();
      if (date == null) {
        log.warn("대출실행 전표 생략: 실행일·시작일이 모두 없습니다. contractNumber={}", c.getContractNumber());
        return;
      }

      String bankName = accountSettings.getPaymentDebitAccount();
      String bankCode = bankName == null ? null : accountResolver.codeOf(bankName);
      if (bankName == null || bankCode == null) {
        bankCode = DEFAULT_BANK_CODE;
        bankName = DEFAULT_BANK_NAME;
      }

      voucherService.create(VoucherCreateRequest.builder()
          .voucherDate(date)
          .contractNumber(c.getContractNumber())
          .memo(EXEC_VOUCHER_MEMO + " / 계약번호: " + c.getContractNumber()
                + (c.getCustomer() != null ? " / 고객명: " + c.getCustomer().getCustomerName() : ""))
          .debitEntries(List.of(VoucherCreateRequest.VoucherLineRequest.builder()
              .accountCode(LoanReceivableAccount.codeOf(c))
              .account(LoanReceivableAccount.nameOf(c))
              .amount(amount)
              .description("대출 실행")
              .build()))
          .creditEntries(List.of(VoucherCreateRequest.VoucherLineRequest.builder()
              .accountCode(bankCode)
              .account(bankName)
              .amount(amount)
              .description("대출금 지급")
              .build()))
          .build());
    } catch (Exception e) {
      log.warn("대출실행 전표 생성 실패 (채권은 그대로 등록됨) contractNumber={} : {}",
          c.getContractNumber(), e.getMessage());
    }
  }

  /** 이 채권의 대출 실행 전표를 지운다. 수납·상각 전표는 건드리지 않는다. */
  private void deleteExecutionVoucher(String contractNumber) {
    if (contractNumber == null || contractNumber.isBlank()) return;
    try {
      for (Voucher v : voucherRepository.findByMemoStartingWithOrderByIdAsc(
              EXEC_VOUCHER_MEMO + " / 계약번호: " + contractNumber)) {
        voucherRepository.delete(v);
      }
    } catch (Exception e) {
      log.warn("대출실행 전표 삭제 실패 contractNumber={} : {}", contractNumber, e.getMessage());
    }
  }

  // ── 검증 / 보조 ──────────────────────────────────────────────

  private void validateRequired(ContractRequest req) {
    if (isBlank(req.customerNumber)) throw new RuntimeException("고객번호(customerNumber) 필수");
    if (isBlank(req.loanType))       throw new RuntimeException("대출구분 필수");
    if (req.loanAmount == null || req.loanAmount <= 0) {
      throw new IllegalArgumentException("대출금은 1원 이상이어야 합니다.");
    }
    if (req.startDate == null) throw new RuntimeException("시작일자 필수");
    if (req.endDate == null)   throw new RuntimeException("종료일자 필수");
    if (req.endDate.isBefore(req.startDate)) {
      throw new IllegalArgumentException("종료일자는 시작일자보다 이전일 수 없습니다.");
    }
    if (req.paymentDay != null && (req.paymentDay < 1 || req.paymentDay > 31)) {
      throw new IllegalArgumentException("납입일자는 1~31 사이여야 합니다.");
    }

    // 대부업법 최고이자율 — 화면 검증만으로는 API 직접 호출을 막지 못하므로 여기서 강제한다.
    LoanRateValidator.validateInterestRate(req.interestRate);
    LoanRateValidator.validateOverdueRate(req.overdueRate, req.interestRate);
  }

  private String normalizeRepaymentMethod(String method) {
    if (isBlank(method)) return RepaymentMethod.EQUAL_PAYMENT;
    String m = method.trim();
    if (!RepaymentMethod.isValid(m)) {
      throw new IllegalArgumentException(
          "상환방식은 " + String.join(" / ", RepaymentMethod.ALL) + " 중 하나여야 합니다. 입력값: " + method);
    }
    return m;
  }

  /** 회차수 미입력 시 시작일자~종료일자 개월수로 계산한다. */
  private int resolveInstallmentCount(Integer given, LocalDate start, LocalDate end) {
    if (given != null && given > 0) return given;
    if (start == null || end == null) {
      throw new IllegalArgumentException("회차수를 입력하거나 시작일자·종료일자를 지정해야 합니다.");
    }
    int months = (int) ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1));
    if (months <= 0) {
      throw new IllegalArgumentException("회차수는 1 이상이어야 합니다. 시작일자와 종료일자를 확인해주세요.");
    }
    return months;
  }

  private long resolveMonthlyPayment(Long given, String method, Long principal,
                                     BigDecimal rate, int installments) {
    if (given != null && given > 0) return given;
    return AmortizationCalculator.monthlyPayment(method, nvl(principal), rate, installments);
  }

  private Contract findById(Long id) {
    return contractRepo.findById(id).orElseThrow(() -> new RuntimeException("채권 없음 id=" + id));
  }

  private String normalizeStatus(String status) {
    if (isBlank(status)) return ContractStatus.NORMAL;
    return status.trim();
  }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }
  private static boolean isNotBlank(String s) { return !isBlank(s); }

  private static long nvl(Long v) { return v == null ? 0L : v; }
  private static int nvl(Integer v) { return v == null ? 0 : v; }

  private static <T> boolean safeEq(T a, T b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    return a.equals(b);
  }

  // ── 응답 변환 ────────────────────────────────────────────────

  private ContractResponse toResponse(Contract c) {
    return ContractResponse.builder()
        .id(c.getId())
        .contractNumber(c.getContractNumber())
        .customerNumber(c.getCustomerNumber())
        .customerName(c.getCustomer() != null ? c.getCustomer().getCustomerName() : null)
        .customerType(c.getCustomerType())
        .loanType(c.getLoanType())
        .debtType(c.getDebtType())
        .loanAmount(c.getLoanAmount())
        .executeDate(c.getExecuteDate())
        .interestRate(c.getInterestRate())
        .overdueRate(c.getOverdueRate())
        .overdueChargeYn(c.getOverdueChargeYn())
        .repaymentMethod(c.getRepaymentMethod())
        .startDate(c.getStartDate())
        .endDate(c.getEndDate())
        .paymentDay(c.getPaymentDay())
        .installmentCount(c.getInstallmentCount())
        .monthlyPayment(c.getMonthlyPayment())
        .status(c.getStatus())
        .remainingPrincipal(c.getRemainingPrincipal())
        .remarks(c.getRemarks())
        .build();
  }

  private ContractFullResponse toFullResponse(Contract c) {
    Customer cu = c.getCustomer();
    return ContractFullResponse.builder()
        .id(c.getId())
        .contractNumber(c.getContractNumber())
        .customerNumber(c.getCustomerNumber())
        .customerName(cu != null ? cu.getCustomerName() : null)
        .customerPhone(cu != null ? cu.getPhone() : null)
        .customerAddress(cu != null ? cu.getAddress() : null)
        .customerRegistrationNumber(cu != null ? cu.getRegistrationNumber() : null)
        .customerType(c.getCustomerType())
        .loanType(c.getLoanType())
        .debtType(c.getDebtType())
        .loanAmount(nvl(c.getLoanAmount()))
        .executeDate(c.getExecuteDate())
        .interestRate(c.getInterestRate())
        .overdueRate(c.getOverdueRate())
        .overdueChargeYn(c.getOverdueChargeYn())
        .repaymentMethod(c.getRepaymentMethod())
        .startDate(c.getStartDate())
        .endDate(c.getEndDate())
        .paymentDay(c.getPaymentDay())
        .installmentCount(nvl(c.getInstallmentCount()))
        .monthlyPayment(nvl(c.getMonthlyPayment()))
        .status(c.getStatus())
        .remainingPrincipal(nvl(c.getRemainingPrincipal()))
        .remarks(c.getRemarks())
        .build();
  }
}
