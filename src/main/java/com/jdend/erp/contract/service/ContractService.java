package com.jdend.erp.contract.service;

import com.jdend.erp.contract.dto.*;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.accounting.voucher.service.VoucherApprovalGuard;
import com.jdend.erp.payment.consultation.repository.ConsultationRepository;
import com.jdend.erp.payment.payment.repository.PaymentRepository;
import com.jdend.erp.payment.receivable.repository.ReceivableRepository;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import com.jdend.erp.contract.entity.InterestCalcType;
import com.jdend.erp.contract.entity.PaymentDayType;
import com.jdend.erp.contract.entity.RepaymentMethod;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.contract.support.AmortizationCalculator;
import com.jdend.erp.contract.support.AmountRounding;
import com.jdend.erp.contract.support.DebtTypeCode;
import com.jdend.erp.contract.policy.LoanPolicyService;
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
import com.jdend.erp.loan.repayment.RepaymentPostingService;
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
  private final VoucherApprovalGuard voucherApprovalGuard;
  private final CustomerRepository customerRepo;
  private final PaymentScheduleAutoGeneratorService scheduleAutoGen;
  private final RepaymentPostingService repaymentPosting;
  private final VoucherService voucherService;
  private final VoucherRepository voucherRepository;
  private final OtherAccountSettingsService accountSettings;
  private final AccountResolver accountResolver;
  private final PaymentScheduleRepository scheduleRepo;
  private final PaymentRepository paymentRepo;
  private final ReceivableRepository receivableRepo;
  private final ConsultationRepository consultationRepo;
  private final LoanPolicyService loanPolicy;

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

  /**
   * 저장하지 않고 등록할 수 있는지만 본다. 엑셀 일괄 업로드의 미리보기가 쓴다.
   *
   * <p>검사 내용이 {@link #create} 와 어긋나면 안 된다 — 미리보기에서 통과한 행이
   * 저장에서 떨어지거나 그 반대가 되면 건수가 맞지 않아 더 헷갈린다. 그래서
   * 같은 {@code validateRequired} 와 같은 {@code buildContract} 를 그대로 쓰고,
   * 저장·스케줄·전표만 하지 않는다.
   */
  @Transactional(readOnly = true)
  public void validateOnly(ContractRequest req) {
    validateRequired(req);
    // 채권번호는 저장할 때 매긴다. 미리보기에서 채번하면 쓰지도 않을 번호를 건너뛴다.
    requireEffectiveRateWithinCap(buildContract(req, "(미리보기)"));
  }

  @Transactional
  public ContractResponse create(ContractRequest req) {
    validateRequired(req);

    Contract c = buildContract(req, generateNextContractNumber(req.loanType));

    requireEffectiveRateWithinCap(c);
    contractRepo.save(c);
    scheduleAutoGen.ensureGenerated(c);

    // 전표를 만들지는 고를 수 있다. 그 시기 전표를 이미 따로 올렸다면 만들지 않는다.
    if (req.createVoucher == null || req.createVoucher) {
      createExecutionVoucher(c);
    }
    return toResponse(c);
  }

  /** 요청을 채권 한 건으로 옮긴다. 저장하지는 않는다. */
  private Contract buildContract(ContractRequest req, String contractNumber) {
    Customer customer = customerRepo.findByCustomerNumber(req.customerNumber).orElse(null);

    String method = normalizeRepaymentMethod(req.repaymentMethod);
    int installments = resolveInstallmentCount(req.installmentCount, req.startDate, req.endDate);
    AmountRounding rounding = AmountRounding.of(req.roundingUnit, req.roundingMode);
    long monthlyPayment = resolveMonthlyPayment(
        req.monthlyPayment, method, req.loanAmount, req.interestRate, installments, rounding);

    return Contract.builder()
        .contractNumber(contractNumber)
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
        .paymentDayType(PaymentDayType.orDefault(req.paymentDayType))
        .interestCalcType(InterestCalcType.orDefault(req.interestCalcType))
        .executeAccount(blankToNullStr(req.executeAccount))
        .roundingUnit(rounding.unit())
        .roundingMode(rounding.mode())
        .installmentCount(installments)
        .monthlyPayment(monthlyPayment)
        .status(normalizeStatus(req.status))
        .remainingPrincipal(nvl(req.loanAmount))
        .remarks(req.remarks)
        .build();
  }

  @Transactional
  public ContractResponse update(Long id, ContractUpdateRequest req) {
    Contract c = findById(id);

    // 기한이익상실·상각 채권은 스케줄이 이미 재편됐다.
    // 여기서 상환방식·기간·금액을 고치면 스케줄이 재생성되면서 접어 둔 '일시청구' 회차가
    // 풀리고, 청구중지 표시가 사라져 미도래 원금에까지 지연배상금이 붙는다.
    // 조건을 고쳐야 하면 기한이익상실을 먼저 취소해야 한다.
    if (ContractStatus.ACCELERATED.equals(c.getStatus())
        || ContractStatus.WRITTEN_OFF.equals(c.getStatus())) {
      if (changesSchedule(req, c)) {
        throw new IllegalArgumentException(
            "'" + c.getStatus() + "' 상태 채권은 대출금·이율·기간·상환방식을 바꿀 수 없습니다. "
                + "기한이익상실(또는 대손상각)을 먼저 취소해주세요.");
      }
    }

    // 수정은 실행 전표를 지우고 다시 만든다. 승인된 전표를 말없이 지우면
    // 이미 장부에 반영된 금액이 소리 없이 바뀐다 — 삭제·수납취소와 같은 규칙을 지킨다.
    requireExecutionVoucherNotApproved(c.getContractNumber());

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
    if (isNotBlank(req.getPaymentDayType())
        && !req.getPaymentDayType().equals(PaymentDayType.orDefault(c.getPaymentDayType()))) {
      c.setPaymentDayType(PaymentDayType.orDefault(req.getPaymentDayType()));
      scheduleAffected = true;
    }
    if (isNotBlank(req.getInterestCalcType())
        && !req.getInterestCalcType().equals(InterestCalcType.orDefault(c.getInterestCalcType()))) {
      c.setInterestCalcType(InterestCalcType.orDefault(req.getInterestCalcType()));
      scheduleAffected = true;
    }
    if (req.getInstallmentCount() != null && !req.getInstallmentCount().equals(c.getInstallmentCount())) {
      c.setInstallmentCount(req.getInstallmentCount());
      scheduleAffected = true;
    }
    if (req.getExecuteAccount() != null) {
      c.setExecuteAccount(blankToNullStr(req.getExecuteAccount()));
    }
    // 단수 설정이 바뀌면 회차 금액이 전부 달라지므로 스케줄을 다시 만든다.
    AmountRounding newRounding = AmountRounding.of(req.getRoundingUnit(), req.getRoundingMode());
    AmountRounding oldRounding = AmountRounding.of(c.getRoundingUnit(), c.getRoundingMode());
    if ((req.getRoundingUnit() != null || req.getRoundingMode() != null)
        && (newRounding.unit() != oldRounding.unit() || !newRounding.mode().equals(oldRounding.mode()))) {
      c.setRoundingUnit(newRounding.unit());
      c.setRoundingMode(newRounding.mode());
      scheduleAffected = true;
    }

    if (c.getEndDate() != null && c.getStartDate() != null && c.getEndDate().isBefore(c.getStartDate())) {
      throw new IllegalArgumentException("종료일자는 시작일자보다 이전일 수 없습니다.");
    }
    BigDecimal cap = loanPolicy.maxInterestRate();
    LoanRateValidator.validateInterestRate(c.getInterestRate(), cap);
    LoanRateValidator.validateOverdueRate(c.getOverdueRate(), c.getInterestRate(), cap);

    // 월납입액은 명시 입력이 있으면 그것을 쓰고, 조건이 바뀌었으면 다시 산출한다.
    if (req.getMonthlyPayment() != null) {
      c.setMonthlyPayment(req.getMonthlyPayment());
    } else if (scheduleAffected) {
      c.setMonthlyPayment(AmortizationCalculator.monthlyPayment(
          c.getRepaymentMethod(), nvl(c.getLoanAmount()), c.getInterestRate(), nvl(c.getInstallmentCount()),
          AmountRounding.of(c.getRoundingUnit(), c.getRoundingMode())));
    }

    requireEffectiveRateWithinCap(c);
    contractRepo.save(c);

    if (scheduleAffected) {
      scheduleAutoGen.regenerate(c);
    } else {
      scheduleAutoGen.ensureGenerated(c);
    }

    // 대출금이 바뀌면 잔여원금도 따라가야 한다. 수납을 다시 흘려보내 계산하므로
    // 수납이 없으면 대출금 전액이 되고, 있으면 그만큼 뺀 금액이 된다.
    // 이걸 빼먹어 대출금만 바뀌고 잔여원금은 옛 금액으로 남아 있었다.
    repaymentPosting.recompute(c.getContractNumber());

    // 대출금·기간·실행일이 바뀌면 실행 분개의 금액이나 계정(단기/장기)이 달라진다.
    // 증분으로 고치면 어긋나기 쉬워 지우고 다시 만든다.
    // 실행 전표는 '있던 건만' 다시 만든다.
    //
    // 전표 없이 옮겨 적은 대출(또는 중복이라 전표를 지운 대출)을 나중에 수정했다고
    // 해서 전표가 되살아나면 안 된다. 지운 쪽은 지운 대로 두는 것이 맞다.
    boolean hadVoucher = findExecutionVoucher(c.getContractNumber()) != null;
    deleteExecutionVoucher(c.getContractNumber());
    if (hadVoucher) {
      createExecutionVoucher(c);
    }

    return toResponse(c);
  }

  @Transactional
  public void delete(Long id) {
    Contract c = contractRepo.findById(id)
        .orElseThrow(() -> new RuntimeException("채권 없음 id=" + id));

    String cn = c.getContractNumber();

    // 대출 실행 전표가 승인된 상태면 먼저 대기로 되돌려야 한다.
    // 수납 취소와 같은 원칙 — 장부에 반영된 전표를 두고 원본만 지우면 어긋난다.
    voucherApprovalGuard.requireNoApprovedForContract(cn, "대출");

    // 수납이 한 건이라도 있으면 지우지 않는다. 돈이 오간 채권을 통째로 없애면
    // 그 수납과 전표가 갈 곳을 잃는다. 수납을 먼저 취소하게 한다.
    if (paymentRepo.existsByContractNumber(cn)) {
      throw new IllegalStateException(
          "이 채권에는 수납 내역이 있어 삭제할 수 없습니다."
          + " 수납관리 > 수납취소에서 수납을 모두 취소한 뒤 다시 시도하세요."
          + " 잘못 등록한 금액이나 조건은 삭제하지 않고 대출등록에서 수정할 수 있습니다.");
    }

    // 등록할 때 함께 만들어진 것들을 먼저 정리한다.
    // 이것들이 계약을 참조하고 있어, 남겨두면 외래키에 걸려 삭제가 되지 않는다.
    scheduleRepo.deleteByContractNumber(cn);
    receivableRepo.deleteByContractNumber(cn);
    consultationRepo.deleteByContractNumber(cn);

    deleteExecutionVoucher(cn);
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
              .description(payoutDescription(c))
              .build()))
          .build());
    } catch (Exception e) {
      log.warn("대출실행 전표 생성 실패 (채권은 그대로 등록됨) contractNumber={} : {}",
          c.getContractNumber(), e.getMessage());
    }
  }

  /**
   * 실행 전표 대변 적요 — 어느 통장에서 나갔는지 함께 적는다.
   *
   * 통장이 여럿이면 계좌가 없는 "대출금 지급" 만으로는 전표와 통장 내역을 맞출 수 없다.
   * 수납 전표가 입금 계좌를 적는 것과 같은 형식으로 둔다.
   */
  private static String payoutDescription(Contract c) {
    String acct = blankToNullStr(c.getExecuteAccount());
    return acct == null ? "대출금 지급" : "대출금 지급 [" + acct + "]";
  }

  private static String blankToNullStr(String s) {
    return (s == null || s.trim().isEmpty()) ? null : s.trim();
  }

  /**
   * 실행 전표가 승인 상태면 수정을 막는다.
   *
   * 수정은 전표를 지우고 다시 만드는 방식이라, 승인된 전표가 조용히 사라지고
   * 금액이 다른 전표로 바뀐다. 승인 전표는 이미 장부에 반영된 것이라
   * 되돌릴 기회 없이 바뀌면 안 된다.
   */
  private void requireExecutionVoucherNotApproved(String contractNumber) {
    Voucher exec = findExecutionVoucher(contractNumber);
    if (exec == null || !VoucherApprovalGuard.APPROVED.equals(exec.getStatus())) return;

    throw new IllegalStateException(
        "대출을 수정하려면 먼저 실행 전표 " + exec.getVoucherNo() + " 의 승인을 취소해야 합니다."
      + " 회계관리 > 전표등록 > 전표승인 에서 채권번호로 찾아 '대기' 버튼을 누른 뒤 다시 시도하세요."
      + " 수정하면 실행 전표를 다시 만들기 때문에, 승인된 전표를 그대로 두면 장부가 어긋납니다.");
  }

  /** 이 채권의 대출 실행 전표. 없으면 null. */
  private Voucher findExecutionVoucher(String contractNumber) {
    if (contractNumber == null || contractNumber.isBlank()) return null;
    return voucherRepository
        .findByMemoStartingWithOrderByIdAsc(EXEC_VOUCHER_MEMO + " / 계약번호: " + contractNumber)
        .stream().findFirst().orElse(null);
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

  /** 스케줄을 다시 만들게 되는 변경인지 */
  private boolean changesSchedule(ContractUpdateRequest req, Contract c) {
    return (req.getLoanAmount() != null && !req.getLoanAmount().equals(c.getLoanAmount()))
        || (req.getInterestRate() != null && !safeEq(req.getInterestRate(), c.getInterestRate()))
        || (isNotBlank(req.getRepaymentMethod()) && !req.getRepaymentMethod().equals(c.getRepaymentMethod()))
        || (req.getStartDate() != null && !req.getStartDate().equals(c.getStartDate()))
        || (req.getEndDate() != null && !req.getEndDate().equals(c.getEndDate()))
        || (req.getPaymentDay() != null && !req.getPaymentDay().equals(c.getPaymentDay()))
        || (isNotBlank(req.getPaymentDayType())
            && !req.getPaymentDayType().equals(PaymentDayType.orDefault(c.getPaymentDayType())))
        || (isNotBlank(req.getInterestCalcType())
            && !req.getInterestCalcType().equals(InterestCalcType.orDefault(c.getInterestCalcType())))
        || (req.getInstallmentCount() != null && !req.getInstallmentCount().equals(c.getInstallmentCount()))
        // 단수 설정도 회차 금액을 바꾼다.
        //
        // 백원단위로 끊기로 하면 회차 청구액이 111,491 에서 111,500 으로 달라진다.
        // 이게 빠져 있어서 계약의 월납입액만 바뀌고 스케줄은 옛 금액으로 남았다.
        // 그러면 청구액과 월납입액이 서로 다른 말을 하게 된다.
        || (req.getRoundingUnit() != null && !req.getRoundingUnit().equals(c.getRoundingUnit()))
        || (isNotBlank(req.getRoundingMode()) && !req.getRoundingMode().equals(c.getRoundingMode()))
        || (req.getMonthlyPayment() != null && !req.getMonthlyPayment().equals(c.getMonthlyPayment()));
  }

  private void validateRequired(ContractRequest req) {
    if (isBlank(req.customerNumber)) throw new RuntimeException("고객번호(customerNumber) 필수");

    // 없는 고객번호는 막는다. 전에는 그대로 통과해 고객이 비어 있는 채권이 생겼다.
    // 화면에서 고를 때는 생길 수 없고 엑셀로 올릴 때 오타로 생기는데, 만들고 나면
    // 채권·스케줄·전표까지 딸려 나와 되돌리기가 번거롭다.
    if (customerRepo.findByCustomerNumber(req.customerNumber.trim()).isEmpty()) {
      throw new IllegalArgumentException("고객번호를 찾을 수 없습니다: " + req.customerNumber
          + " — 고객관리에 먼저 등록하거나 번호를 확인하세요.");
    }
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
    if (req.paymentDayType != null && !PaymentDayType.isValid(req.paymentDayType)) {
      throw new IllegalArgumentException("납입일자 방식은 '일자' 또는 '말일'이어야 합니다.");
    }
    if (req.interestCalcType != null && !InterestCalcType.isValid(req.interestCalcType)) {
      throw new IllegalArgumentException("이자 계산 방식은 '월할' 또는 '일할'이어야 합니다.");
    }
    if (!AmountRounding.isValidUnit(req.roundingUnit)) {
      throw new IllegalArgumentException("납입금액 단수 단위는 1(원), 10(십원), 100(백원) 중 하나여야 합니다.");
    }
    if (!AmountRounding.isValidMode(req.roundingMode)) {
      throw new IllegalArgumentException("단수 처리 방식은 '절사', '절상', '반올림' 중 하나여야 합니다.");
    }
    // 원리금균등은 매회 납입액이 같은 것이 정의라 일할과 양립하지 않는다.
    if (!InterestCalcType.isAllowedFor(req.repaymentMethod, req.interestCalcType)) {
      throw new IllegalArgumentException(
          "원리금균등은 매회 납입액이 같은 방식이라 일할을 쓸 수 없습니다. "
          + "일할이 필요하면 원금균등이나 만기일시를 선택하세요.");
    }

    // 대부업법 최고이자율 — 화면 검증만으로는 API 직접 호출을 막지 못하므로 여기서 강제한다.
    BigDecimal cap = loanPolicy.maxInterestRate();
    LoanRateValidator.validateInterestRate(req.interestRate, cap);
    LoanRateValidator.validateOverdueRate(req.overdueRate, req.interestRate, cap);
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

  /**
   * 단수 처리까지 반영한 실효 연이율이 법정 상한을 넘는지 본다.
   *
   * 명목이율 검증만으로는 절상을 잡지 못한다. 저장될 스케줄을 미리 만들어
   * 실제로 매겨질 이자로 확인한다.
   */
  private void requireEffectiveRateWithinCap(Contract c) {
    LoanRateValidator.validateEffectiveRate(
        scheduleAutoGen.effectiveAnnualRate(c, scheduleAutoGen.preview(c)),
        loanPolicy.maxInterestRate());
  }

  private long resolveMonthlyPayment(Long given, String method, Long principal,
                                     BigDecimal rate, int installments, AmountRounding rounding) {
    if (given != null && given > 0) return given;
    return AmortizationCalculator.monthlyPayment(method, nvl(principal), rate, installments, rounding);
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
        .paymentDayType(PaymentDayType.orDefault(c.getPaymentDayType()))
        .interestCalcType(InterestCalcType.orDefault(c.getInterestCalcType()))
        .executeAccount(c.getExecuteAccount())
        .roundingUnit(AmountRounding.of(c.getRoundingUnit(), c.getRoundingMode()).unit())
        .roundingMode(AmountRounding.of(c.getRoundingUnit(), c.getRoundingMode()).mode())
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
        .paymentDayType(PaymentDayType.orDefault(c.getPaymentDayType()))
        .interestCalcType(InterestCalcType.orDefault(c.getInterestCalcType()))
        .executeAccount(c.getExecuteAccount())
        .roundingUnit(AmountRounding.of(c.getRoundingUnit(), c.getRoundingMode()).unit())
        .roundingMode(AmountRounding.of(c.getRoundingUnit(), c.getRoundingMode()).mode())
        .installmentCount(nvl(c.getInstallmentCount()))
        .monthlyPayment(nvl(c.getMonthlyPayment()))
        .status(c.getStatus())
        .remainingPrincipal(nvl(c.getRemainingPrincipal()))
        .remarks(c.getRemarks())
        .build();
  }
}
