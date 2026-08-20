package com.jdend.erp.payment.payment.service;

import com.jdend.erp.accounting.prepaidrent.service.PrepaidRentService;
import com.jdend.erp.accounting.settings.service.OtherAccountSettingsService;
import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import com.jdend.erp.accounting.voucher.service.VoucherNumberService;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
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
        .vehicleNo(c.getVehicleNo())
        .paymentDate(req.getPaymentDate())
        .paymentAmount(req.getPaymentAmount())
        .paymentMethod(req.getPaymentMethod())
        .companyAccount(req.getCompanyAccount())
        .memo(req.getMemo())
        .build());

    boolean shouldCreateVoucher = req.getCreateVoucher() == null || req.getCreateVoucher();
    if (shouldCreateVoucher) {
      // BUG-03: createVoucherIfNeeded가 생성된 전표 ID를 반환하고 Payment에 저장
      Long voucherId = createVoucherIfNeeded(saved, totalDue, excess);
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
      p.setVehicleNo(c.getVehicleNo());
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

    boolean shouldCreateVoucher = req.getCreateVoucher() == null || req.getCreateVoucher();
    if (shouldCreateVoucher) {
      Long newVoucherId = createVoucherIfNeeded(p, totalDue, excess);
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
  }

  /**
   * BUG-03: 전표 생성 후 생성된 전표 ID를 반환한다.
   * @param totalDue  미납 미수금 합계 (excess > 0 일 때 렌트수익 대변 금액)
   * @param excess    초과 수납액 → 선수금 대변
   * @return 생성된 Voucher ID, 생성 조건 미충족 시 null
   */
  private Long createVoucherIfNeeded(Payment payment, long totalDue, long excess) {
    if (payment == null) return null;
    if (payment.getPaymentAmount() == null || payment.getPaymentAmount() <= 0) return null;

    // 기타계정관리에서 설정한 계정명 사용. 미설정이면 전표를 생성하지 않는다.
    String debitAccount  = accountSettings.getPaymentDebitAccount();
    String creditAccount = accountSettings.getPaymentCreditAccount();
    if (debitAccount == null || creditAccount == null) {
      log.warn("수납 전표 생략: 기타계정관리 > 수납 전표의 차변/대변을 설정해주세요. paymentId={}", payment.getId());
      return null;
    }

    LocalDate voucherDate = payment.getPaymentDate() != null ? payment.getPaymentDate() : LocalDate.now();
    String voucherNo = nextVoucherNo(voucherDate);
    String memo = buildPaymentVoucherMemo(payment);

    Voucher voucher = Voucher.builder()
        .voucherNo(voucherNo)
        .voucherDate(voucherDate)
        .contractNumber(blankToNull(payment.getContractNumber()))
        .vehicleNo(blankToNull(payment.getVehicleNo()))
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

    if (excess > 0 && totalDue > 0) {
      // 초과 수납: 대변 ① 수익 + ② 선수금 (초과분)
      addRevenueCreditLine(voucher, creditAccount, totalDue, 2);
      voucher.addLine(VoucherLine.builder()
          .lineType("CREDIT")
          .accountCode(prepaidAccountCode())
          .accountName("선수금")
          .amount(excess)
          .description("초과수납 선수금")
          .sortOrder(3)
          .build());
    } else if (excess > 0) {
      // 미수금 0인데 전액 초과: 대변 선수금 전액
      voucher.addLine(VoucherLine.builder()
          .lineType("CREDIT")
          .accountCode(prepaidAccountCode())
          .accountName("선수금")
          .amount(payment.getPaymentAmount())
          .description("선수금 입금")
          .sortOrder(2)
          .build());
    } else {
      // 일반 수납: 대변 수익 전액
      addRevenueCreditLine(voucher, creditAccount, payment.getPaymentAmount(), 2);
    }

    Voucher saved = voucherRepository.save(voucher);
    return saved.getId();
  }

  /** 선수금 계정코드: 기타계정관리 설정 우선, 없으면 계정명으로 조회 */
  private String prepaidAccountCode() {
    String code = accountSettings.getPrepaidDebitAccountCode();
    if (code != null && !code.isBlank()) return code;
    return accountResolver.codeOf("선수금");
  }

  /**
   * 수익 대변 라인을 추가한다.
   * 대부업 이자수익은 부가가치세 면세이므로 공급가액/부가세 분리 없이 총액으로 계상한다.
   */
  private void addRevenueCreditLine(Voucher voucher, String creditAccount,
                                    long amount, int startOrder) {
    voucher.addLine(VoucherLine.builder()
        .lineType("CREDIT")
        .accountCode(accountResolver.codeOf(creditAccount))
        .accountName(creditAccount)
        .amount(amount)
        .description("수납등록 입금")
        .sortOrder(startOrder)
        .build());
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
        .vehicleNo(p.getVehicleNo())
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
