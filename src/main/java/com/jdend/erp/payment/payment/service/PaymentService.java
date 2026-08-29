package com.jdend.erp.payment.payment.service;

import com.jdend.erp.accounting.prepaidrent.service.PrepaidRentService;
import com.jdend.erp.accounting.settings.service.OtherAccountSettingsService;
import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import com.jdend.erp.accounting.voucher.service.VoucherNumberService;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.loan.repayment.RepaymentAllocation;
import com.jdend.erp.loan.support.LoanReceivableAccount;
import com.jdend.erp.loan.repayment.RepaymentPostingService;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import com.jdend.erp.payment.payment.dto.PaymentResponse;
import com.jdend.erp.payment.payment.dto.PaymentUpsertRequest;
import com.jdend.erp.payment.payment.entity.Payment;
import com.jdend.erp.payment.payment.repository.PaymentRepository;
import com.jdend.erp.payment.receivable.entity.Receivable;
import com.jdend.erp.payment.receivable.repository.ReceivableRepository;
import com.jdend.erp.accounting.voucher.service.AccountResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

  private final PaymentRepository paymentRepo;
  private final ContractRepository contractRepo;
  private final VoucherRepository voucherRepository;
  private final OtherAccountSettingsService accountSettings;
  private final AccountResolver accountResolver;
  private final ReceivableRepository receivableRepo;  // 미수현황 별도 관리용 (분개 기준 아님)
  private final VoucherNumberService voucherNumberService;
  private final PrepaidRentService prepaidRentService;
  private final PaymentScheduleRepository paymentScheduleRepo;
  private final RepaymentPostingService repaymentPosting;

  @Transactional(readOnly = true)
  public Page<PaymentResponse> list(String kw, int page, int size) {
    Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    return paymentRepo.search(kw, pageable).map(this::toResponse);
  }

  @Transactional(readOnly = true)
  public PaymentResponse get(Long id) {
    Payment p = paymentRepo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("수납 ID를 찾을 수 없습니다: " + id));
    return toResponse(p);
  }

  @Transactional(readOnly = true)
  public List<PaymentResponse> listByContractNumber(String contractNumber) {
    return paymentRepo.findByContractNumberOrderByPaymentDateAscIdAsc(contractNumber)
        .stream().map(this::toResponse).toList();
  }

  @Transactional
  public PaymentResponse create(PaymentUpsertRequest req) {
    validate(req);

    Contract c = contractRepo.findWithCustomerByContractNumber(req.getContractNumber().trim())
        .orElseThrow(() -> new IllegalArgumentException("계약번호를 찾을 수 없습니다: " + req.getContractNumber()));

    Customer cu = c.getCustomer();

    // 저장 전에 먼저 계산 — 저장 후 조회하면 현재 수납액이 alreadyPaid에 포함되어 totalDue가 0이 됨
    LocalDate paymentDate = req.getPaymentDate() != null ? req.getPaymentDate() : LocalDate.now();
    long totalDue = calcTotalDue(c.getContractNumber(), paymentDate);
    long excess   = Math.max(0L, req.getPaymentAmount() - totalDue);


    Payment saved = paymentRepo.save(Payment.builder()
        .contractId(c.getId())
        .contractNumber(c.getContractNumber())
        .customerId(cu != null ? cu.getId() : null)
        .customerNumber(c.getCustomerNumber())
        .customerName(cu != null ? cu.getCustomerName() : null)
        .paymentDate(req.getPaymentDate())
        .paymentAmount(req.getPaymentAmount())
        .paymentMethod(req.getPaymentMethod())
        .companyAccount(req.getCompanyAccount())
        .memo(req.getMemo())
        .build());

    // 변제충당 반영 — 회차별 원금/이자/지연배상금 충당 실적을 다시 계산한다.
    RepaymentAllocation alloc = repaymentPosting.recompute(saved.getContractNumber());
    excess = alloc.getExcess();

    boolean shouldCreateVoucher = req.getCreateVoucher() == null || req.getCreateVoucher();
    if (shouldCreateVoucher) {
      Long voucherId = createVoucherIfNeeded(saved, alloc);
      if (voucherId != null) {
        saved.setVoucherId(voucherId);
        paymentRepo.save(saved);
      }
    }

    // BUG-10: 수납 등록 후 납기일 이전(포함) 미납 미수금 상태 업데이트
    updateReceivableStatus(saved.getContractNumber(), saved.getPaymentAmount(), paymentDate);

    // 초과금액 → 선수금 자동 등록
    if (excess > 0 && saved.getContractId() != null) {
      prepaidRentService.registerFromPaymentExcess(
          saved.getContractId(), excess, saved.getPaymentDate(), saved.getMemo(), saved.getId());
    }

    return toResponse(saved);
  }

  @Transactional
  public PaymentResponse update(Long id, PaymentUpsertRequest req) {
    validate(req);

    Payment p = paymentRepo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("수납 ID를 찾을 수 없습니다: " + id));

    // 수정 전 자동 등록된 선수금 레코드 삭제 (누적 방지)
    prepaidRentService.deleteByPaymentReference(p.getContractId(), id);

    // BUG-03: 수정 전 미수금 상태 복구
    restoreReceivableStatus(p.getContractNumber(), p.getPaymentAmount());

    // BUG-03: 기존 연결 전표 삭제
    if (p.getVoucherId() != null) {
      voucherRepository.findById(p.getVoucherId()).ifPresent(v -> {
        voucherRepository.delete(v);
        log.info("수납 수정: 기존 전표 삭제 voucherId={}", p.getVoucherId());
      });
      p.setVoucherId(null);
    }

    String newCn = req.getContractNumber() == null ? "" : req.getContractNumber().trim();
    if (!newCn.isEmpty() && !newCn.equals(p.getContractNumber())) {
      Contract c = contractRepo.findWithCustomerByContractNumber(newCn)
          .orElseThrow(() -> new IllegalArgumentException("계약번호를 찾을 수 없습니다: " + newCn));

      Customer cu = c.getCustomer();
      p.setContractId(c.getId());
      p.setContractNumber(c.getContractNumber());
      p.setCustomerId(cu != null ? cu.getId() : null);
      p.setCustomerNumber(c.getCustomerNumber());
      p.setCustomerName(cu != null ? cu.getCustomerName() : null);
    }

    p.setPaymentDate(req.getPaymentDate());
    p.setPaymentAmount(req.getPaymentAmount());
    p.setPaymentMethod(req.getPaymentMethod());
    p.setCompanyAccount(req.getCompanyAccount());
    p.setMemo(req.getMemo());

    // 수정 후 전표 재생성 — create 와 동일하게 excess 계산(기존 수납 id 제외)
    LocalDate updatedPaymentDate = req.getPaymentDate() != null ? req.getPaymentDate() : LocalDate.now();
    long totalDue = calcTotalDue(p.getContractNumber(), updatedPaymentDate, id);
    long excess   = Math.max(0L, req.getPaymentAmount() - totalDue);

    // 수정된 금액·일자로 충당을 처음부터 다시 계산한다.
    RepaymentAllocation alloc = repaymentPosting.recompute(p.getContractNumber());
    excess = alloc.getExcess();

    boolean shouldCreateVoucher = req.getCreateVoucher() == null || req.getCreateVoucher();
    if (shouldCreateVoucher) {
      Long newVoucherId = createVoucherIfNeeded(p, alloc);
      if (newVoucherId != null) {
        p.setVoucherId(newVoucherId);
      }
    }

    paymentRepo.save(p);

    // 수정 후 새 금액으로 납기일 이전(포함) 미수금 상태 재적용
    updateReceivableStatus(p.getContractNumber(), req.getPaymentAmount(), updatedPaymentDate);

    // 초과금액 → 선수금 자동 등록 (create 와 동일)
    if (excess > 0 && p.getContractId() != null) {
      prepaidRentService.registerFromPaymentExcess(
          p.getContractId(), excess, p.getPaymentDate(), p.getMemo(), p.getId());
    }

    return toResponse(p);
  }

  @Transactional
  public void delete(Long id) {
    Payment p = paymentRepo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("수납 ID를 찾을 수 없습니다: " + id));

    // BUG-03: 연결 전표 먼저 삭제
    if (p.getVoucherId() != null) {
      voucherRepository.findById(p.getVoucherId()).ifPresent(v -> {
        voucherRepository.delete(v);
        log.info("수납 삭제: 연결 전표 삭제 voucherId={}", p.getVoucherId());
      });
    }

    // BUG-03: 삭제 시 미수금 상태 복구
    restoreReceivableStatus(p.getContractNumber(), p.getPaymentAmount());

    // 자동 등록된 선수금 레코드 삭제
    prepaidRentService.deleteByPaymentReference(p.getContractId(), id);

    paymentRepo.deleteById(id);

    // 삭제된 수납을 제외하고 충당을 다시 계산한다.
    repaymentPosting.recompute(p.getContractNumber());
  }

  // 대여금(대출채권) 계정은 계약마다 다르다.
  // 대출기간 1년 미만이면 단기대여금, 1년 이상이면 장기대여금.
  // 실행 전표에서 차변으로 잡은 계정과 반드시 같아야 계정 잔액이 맞는다.
  //   -> LoanReceivableAccount.codeOf(contract)
  /** 이자수익 (영업수익) */
  private static final String ACC_INTEREST_REVENUE = "400101";
  /** 연체이자수익 (영업수익) */
  private static final String ACC_OVERDUE_REVENUE = "400102";
  /** 상각채권추심이익 (영업수익) */
  private static final String ACC_RECOVERY_REVENUE = "400103";
  /** 법무비용 (영업비용) — 법적비용 회수분 환입 */
  private static final String ACC_LEGAL_COST = "500102";

  /**
   * 수납 전표를 변제충당 결과에 맞춰 항목별로 나눠 생성한다.
   *
   *   (차) 보통예금 [입금 전액]
   *   (대) 단기/장기대여금 [원금] + 이자수익 [이자] + 연체이자수익 [지연배상금]
   *        + 법무비용 [비용 회수] + 선수금 [초과분]
   *
   * 상각 채권 회수는 원금·이자를 가리지 않고 상각채권추심이익으로 인식한다.
   *
   * @return 생성된 Voucher ID, 생성 조건 미충족 시 null
   */
  private Long createVoucherIfNeeded(Payment payment, RepaymentAllocation alloc) {
    if (payment == null) return null;
    if (payment.getPaymentAmount() == null || payment.getPaymentAmount() <= 0) return null;

    // 입금 계정(보통예금)은 기타계정관리 설정을 따른다. 미설정이면 전표를 만들지 않는다.
    String debitAccount = accountSettings.getPaymentDebitAccount();
    if (debitAccount == null) {
      log.warn("수납 전표 생략: 기타계정관리 > 수납 전표의 차변 계정을 설정해주세요. paymentId={}", payment.getId());
      return null;
    }
    if (alloc == null) alloc = new RepaymentAllocation();

    LocalDate voucherDate = payment.getPaymentDate() != null ? payment.getPaymentDate() : LocalDate.now();
    String voucherNo = nextVoucherNo(voucherDate);
    String memo = buildPaymentVoucherMemo(payment);

    Voucher voucher = Voucher.builder()
        .voucherNo(voucherNo)
        .voucherDate(voucherDate)
        .contractNumber(blankToNull(payment.getContractNumber()))
        .totalAmount(payment.getPaymentAmount())
        .status("대기")
        .memo(memo)
        .build();

    // 차변: 보통예금 (전액)
    String debitDesc = "수납등록 입금";
    String compAcct = blankToNull(payment.getCompanyAccount());
    if (compAcct != null) debitDesc += " [" + compAcct + "]";
    voucher.addLine(VoucherLine.builder()
        .lineType("DEBIT")
        .accountCode(accountResolver.codeOf(debitAccount))
        .accountName(debitAccount)
        .amount(payment.getPaymentAmount())
        .description(debitDesc)
        .sortOrder(1)
        .build());

    boolean writtenOff = ContractStatus.WRITTEN_OFF.equals(contractStatusOf(payment.getContractNumber()));
    int order = 2;

    if (alloc.getCost() > 0) {
      order = addCredit(voucher, ACC_LEGAL_COST, "법무비용", alloc.getCost(), "법적비용 회수", order);
    }
    if (writtenOff) {
      // 상각채권 회수는 원금·이자를 구분하지 않고 추심이익으로 인식한다.
      long recovered = alloc.getPrincipal() + alloc.getInterest() + alloc.getOverdueInterest();
      if (recovered > 0) {
        order = addCredit(voucher, ACC_RECOVERY_REVENUE, "상각채권추심이익", recovered, "상각채권 회수", order);
      }
    } else {
      if (alloc.getOverdueInterest() > 0) {
        order = addCredit(voucher, ACC_OVERDUE_REVENUE, "연체이자수익", alloc.getOverdueInterest(), "지연배상금 수납", order);
      }
      if (alloc.getInterest() > 0) {
        order = addCredit(voucher, ACC_INTEREST_REVENUE, "이자수익", alloc.getInterest(), "이자 수납", order);
      }
      if (alloc.getPrincipal() > 0) {
        Contract loanContract = contractRepo.findByContractNumber(payment.getContractNumber()).orElse(null);
        order = addCredit(voucher,
            LoanReceivableAccount.codeOf(loanContract),
            LoanReceivableAccount.nameOf(loanContract),
            alloc.getPrincipal(), "원금 회수", order);
      }
    }
    if (alloc.getExcess() > 0) {
      voucher.addLine(VoucherLine.builder()
          .lineType("CREDIT")
          .accountCode(prepaidAccountCode())
          .accountName("선수금")
          .amount(alloc.getExcess())
          .description("초과수납 선수금")
          .sortOrder(order++)
          .build());
    }

    // 충당이 하나도 안 잡히면(스케줄 없음 등) 대변이 비어 대차가 안 맞는다.
    // 이 경우 전액을 선수금으로 받아 둔다.
    if (voucher.getLines().stream().noneMatch(l -> "CREDIT".equals(l.getLineType()))) {
      voucher.addLine(VoucherLine.builder()
          .lineType("CREDIT")
          .accountCode(prepaidAccountCode())
          .accountName("선수금")
          .amount(payment.getPaymentAmount())
          .description("선수금 입금 (충당 대상 회차 없음)")
          .sortOrder(2)
          .build());
    }

    Voucher saved = voucherRepository.save(voucher);
    return saved.getId();
  }

  private int addCredit(Voucher voucher, String code, String name, long amount, String desc, int order) {
    voucher.addLine(VoucherLine.builder()
        .lineType("CREDIT")
        .accountCode(code)
        .accountName(name)
        .amount(amount)
        .description(desc)
        .sortOrder(order)
        .build());
    return order + 1;
  }

  private String contractStatusOf(String contractNumber) {
    if (contractNumber == null) return null;
    return contractRepo.findByContractNumber(contractNumber)
        .map(Contract::getStatus)
        .orElse(null);
  }

  /** 선수금 계정코드: 기타계정관리 설정 우선, 없으면 계정명으로 조회 */
  private String prepaidAccountCode() {
    String code = accountSettings.getPrepaidDebitAccountCode();
    if (code != null && !code.isBlank()) return code;
    return accountResolver.codeOf("선수금");
  }

  /**
   * 납기일(taxInvoiceDate) 이전(포함) 스케줄 합계 - 기존 수납 합계를 차감한 잔여 미납액.
   * 워터폴 방식으로 계산하므로 paymentDate IS NULL 조건에 의존하지 않는다.
   */
  private long calcTotalDue(String contractNumber, LocalDate asOfDate) {
    return calcTotalDue(contractNumber, asOfDate, null);
  }

  /**
   * excludePaymentId 를 제외한 수납 합계로 미납액 계산.
   * 수납 수정(update) 시 기존 수납을 이중으로 포함하지 않도록 사용한다.
   */
  private long calcTotalDue(String contractNumber, LocalDate asOfDate, Long excludePaymentId) {
    if (contractNumber == null || contractNumber.isBlank()) return 0L;
    List<PaymentSchedule> due = paymentScheduleRepo.findDueByContractNumberAndTaxInvoiceDateLTE(contractNumber, asOfDate);
    if (due.isEmpty()) return 0L;
    long totalDue = due.stream().mapToLong(ps -> ps.getRentAmount() == null ? 0L : ps.getRentAmount()).sum();
    long alreadyPaid = paymentRepo.findByContractNumberOrderByPaymentDateAscIdAsc(contractNumber)
        .stream()
        .filter(p -> excludePaymentId == null || !excludePaymentId.equals(p.getId()))
        .mapToLong(p -> p.getPaymentAmount() == null ? 0L : p.getPaymentAmount()).sum();
    return Math.max(0L, totalDue - alreadyPaid);
  }

  /**
   * 수납 등록 시 납기일 이전(포함) 미납 스케줄을 납부 처리한다.
   * PaymentSchedules.paymentDate = 수납일자 로 업데이트.
   * 초과분은 선수금으로 별도 처리되므로 미래 스케줄은 건드리지 않는다.
   */
  private void updateReceivableStatus(String contractNumber, Long paymentAmount, LocalDate asOfDate) {
    if (contractNumber == null || contractNumber.isBlank()) return;
    if (paymentAmount == null || paymentAmount <= 0) return;

    List<PaymentSchedule> unpaid =
        paymentScheduleRepo.findUnpaidByContractNumberAndDateLTE(contractNumber, asOfDate);
    if (unpaid.isEmpty()) return;

    long remaining = paymentAmount;
    for (PaymentSchedule ps : unpaid) {
      long amt = ps.getRentAmount() == null ? 0L : ps.getRentAmount();
      if (remaining >= amt) {
        ps.setPaymentDate(asOfDate);
        paymentScheduleRepo.save(ps);
        remaining -= amt;
      } else {
        break;
      }
    }
  }

  /** 수납 삭제/수정 시 납부 처리된 스케줄을 역순으로 미납(paymentDate = null)으로 되돌린다. */
  private void restoreReceivableStatus(String contractNumber, Long paymentAmount) {
    if (contractNumber == null || contractNumber.isBlank()) return;
    if (paymentAmount == null || paymentAmount <= 0) return;

    List<PaymentSchedule> paid =
        paymentScheduleRepo.findPaidByContractNumberOrderByDateDesc(contractNumber);
    if (paid.isEmpty()) return;

    long toReverse = paymentAmount;
    for (PaymentSchedule ps : paid) {
      long amt = ps.getRentAmount() == null ? 0L : ps.getRentAmount();
      if (toReverse >= amt) {
        ps.setPaymentDate(null);
        paymentScheduleRepo.save(ps);
        toReverse -= amt;
      } else {
        break;
      }
    }
  }

  private String nextVoucherNo(LocalDate date) {
    return voucherNumberService.next(date);
  }

  private String buildPaymentVoucherMemo(Payment payment) {
    String contractNumber = blankToNull(payment.getContractNumber());
    String customerName = blankToNull(payment.getCustomerName());
    String companyAccount = blankToNull(payment.getCompanyAccount());

    StringBuilder sb = new StringBuilder("수납등록");
    if (contractNumber != null) sb.append(" / 계약번호: ").append(contractNumber);
    if (customerName != null) sb.append(" / 고객명: ").append(customerName);
    if (companyAccount != null) sb.append(" / 당사계좌: ").append(companyAccount);
    return sb.toString();
  }

  private void validate(PaymentUpsertRequest req) {
    if (req.getContractNumber() == null || req.getContractNumber().trim().isEmpty()) {
      throw new IllegalArgumentException("contractNumber는 필수입니다.");
    }
    if (req.getPaymentDate() == null) {
      throw new IllegalArgumentException("paymentDate는 필수입니다.");
    }
    if (req.getPaymentAmount() == null || req.getPaymentAmount() <= 0) {
      throw new IllegalArgumentException("paymentAmount는 1 이상이어야 합니다.");
    }
  }

  private PaymentResponse toResponse(Payment p) {
    return PaymentResponse.builder()
        .id(p.getId())
        .contractNumber(p.getContractNumber())
        .customerName(p.getCustomerName())
        .paymentDate(p.getPaymentDate())
        .paymentAmount(p.getPaymentAmount())
        .paymentMethod(p.getPaymentMethod())
        .companyAccount(p.getCompanyAccount())
        .memo(p.getMemo())
        .voucherId(p.getVoucherId())
        .build();
  }

  private String blankToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  private String blankToEmpty(String s) {
    return s == null ? "" : s.trim();
  }
}
