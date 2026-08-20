package com.jdend.erp.contract.earlytermination.service;

import com.jdend.erp.accounting.settings.service.OtherAccountSettingsService;
import com.jdend.erp.accounting.voucher.dto.VoucherCreateRequest;
import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import com.jdend.erp.accounting.voucher.service.VoucherService;
import com.jdend.erp.contract.earlytermination.dto.*;
import com.jdend.erp.contract.earlytermination.entity.EarlyTermination;
import com.jdend.erp.contract.earlytermination.repository.EarlyTerminationRepository;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.entity.ContractStatus;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.customer.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EarlyTerminationService {

  private final EarlyTerminationRepository earlyTerminationRepository;
  private final ContractRepository contractRepository;
  private final CustomerRepository customerRepository;
  private final VoucherService voucherService;
  private final VoucherRepository voucherRepository;
  private final OtherAccountSettingsService accountSettings;

  @Transactional(readOnly = true)
  public Page<EarlyTerminationRowDto> list(String status, int page, int size) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "id"));

    Page<EarlyTermination> result;
    if (status == null || status.isBlank() || "all".equalsIgnoreCase(status) || "전체".equals(status)) {
      result = earlyTerminationRepository.findAll(pageable);
    } else {
      result = earlyTerminationRepository.findByStatus(status, pageable);
    }

    return result.map(this::toRowDto);
  }

  @Transactional(readOnly = true)
  public EarlyTerminationDetailResponse get(Long id) {
    EarlyTermination et = earlyTerminationRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("중도상환 데이터를 찾을 수 없습니다. id=" + id));
    return toDetailDto(et);
  }

  // NEW-BUG-C: 허용된 해지방법 값 목록 (early_termination.html 선택 옵션 기준)
  private static final List<String> VALID_TERMINATION_METHODS = List.of("전액상환", "일부상환");

  public Long create(EarlyTerminationCreateRequest req) {
    if (req.getTerminationFee() == null) req.setTerminationFee(0L);
    if (req.getUncollectedRent() == null) req.setUncollectedRent(0L);

    // NEW-BUG-C: terminationMethod 유효값 검증
    if (req.getTerminationMethod() == null || req.getTerminationMethod().isBlank()) {
      throw new IllegalArgumentException("중도상환방법을 선택해주세요. 허용값: " + VALID_TERMINATION_METHODS);
    }
    if (!VALID_TERMINATION_METHODS.contains(req.getTerminationMethod())) {
      throw new IllegalArgumentException(
          "지원하지 않는 중도상환방법입니다: '" + req.getTerminationMethod() + "'. 허용값: " + VALID_TERMINATION_METHODS);
    }

    Contract contract = contractRepository.findByContractNumber(req.getContractNumber())
        .orElseThrow(() -> new IllegalArgumentException("계약번호를 찾을 수 없습니다: " + req.getContractNumber()));

    String customerName = resolveCustomerName(contract);
    LocalDate terminationDate = (req.getTerminationDate() != null) ? req.getTerminationDate() : LocalDate.now();

    // BUG-08 수정: 해지일이 계약 시작일보다 이전이면 거부
    if (terminationDate.isBefore(contract.getStartDate())) {
      throw new IllegalArgumentException(
          "해지일은 계약 시작일(" + contract.getStartDate() + ") 이후여야 합니다.");
    }

    long terminationAmount = safe(req.getTerminationAmount());
    long uncollectedRent = safe(req.getUncollectedRent());   // ✅ 직접 입력값 사용
    long terminationFee = safe(req.getTerminationFee());
    long totalAmount = terminationAmount + uncollectedRent + terminationFee;

    EarlyTermination et = EarlyTermination.builder()
        .contractId(contract.getId())
        .contractNumber(contract.getContractNumber())
        .customerName(customerName)
        .contractType(contract.getLoanType())
        .startDate(contract.getStartDate())
        .endDate(contract.getEndDate())
        .monthlyRent(contract.getMonthlyPayment())
        .totalRent(contract.getLoanAmount())
        .terminationMethod(req.getTerminationMethod())
        .terminationDate(terminationDate)
        .status(req.getStatus())
        .terminationAmount(terminationAmount)
        .uncollectedRent(uncollectedRent)   // ✅ 직접 입력값 저장
        .terminationFee(terminationFee)
        .totalAmount(totalAmount)
        .companyAccount(req.getCompanyAccount())
        .build();

    EarlyTermination saved = earlyTerminationRepository.save(et);

    // 전액상환이 처리완료되면 채권을 완제(종료) 처리한다.
    // 일부상환은 원금 일부만 줄어들 뿐이므로 채권 상태를 바꾸지 않는다
    // (정상/연체는 미납 스케줄로 조회 시점에 판정된다).
    if ("처리완료".equals(saved.getStatus()) && isReturnCompleted(saved)) {
      createReturnVoucher(saved);
      updateContractStatus(saved.getContractNumber(), ContractStatus.CLOSED);
    }

    return saved.getId();
  }

  public void update(Long id, EarlyTerminationUpdateRequest req) {
    if (req.getTerminationFee() == null) req.setTerminationFee(0L);
    if (req.getUncollectedRent() == null) req.setUncollectedRent(0L);

    // BUG-⑧ 수정: create()와 동일하게 terminationMethod 유효값 검증 추가
    if (req.getTerminationMethod() == null || req.getTerminationMethod().isBlank()) {
      throw new IllegalArgumentException("중도상환방법을 선택해주세요. 허용값: " + VALID_TERMINATION_METHODS);
    }
    if (!VALID_TERMINATION_METHODS.contains(req.getTerminationMethod())) {
      throw new IllegalArgumentException(
          "지원하지 않는 중도상환방법입니다: '" + req.getTerminationMethod() + "'. 허용값: " + VALID_TERMINATION_METHODS);
    }

    EarlyTermination et = earlyTerminationRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("중도상환 데이터를 찾을 수 없습니다. id=" + id));

    String prevStatus = et.getStatus();
    String prevMethod = et.getTerminationMethod();

    LocalDate terminationDate = (req.getTerminationDate() != null) ? req.getTerminationDate() : et.getTerminationDate();

    long terminationAmount = safe(req.getTerminationAmount());
    long uncollectedRent = safe(req.getUncollectedRent());
    long terminationFee = safe(req.getTerminationFee());
    long totalAmount = terminationAmount + uncollectedRent + terminationFee;

    et.setTerminationMethod(req.getTerminationMethod());
    et.setTerminationDate(terminationDate);
    et.setStatus(req.getStatus());

    et.setTerminationAmount(terminationAmount);
    et.setUncollectedRent(uncollectedRent);
    et.setTerminationFee(terminationFee);
    et.setTotalAmount(totalAmount);
    et.setCompanyAccount(req.getCompanyAccount());

    boolean wasReturnCompleted = "전액상환".equals(prevMethod) && "처리완료".equals(prevStatus);
    boolean isNowReturnCompleted = isReturnCompleted(et);
    boolean wasCompleted = "처리완료".equals(prevStatus);
    boolean isNowCompleted = "처리완료".equals(et.getStatus());

    if (wasReturnCompleted && isNowReturnCompleted) {
      // BUG-F03: 이미 처리완료 상태에서 금액 수정 → 기존 전표 삭제 후 재생성
      if (et.getContractNumber() != null) {
        List<Voucher> oldVouchers = voucherRepository.findByContractNumberAndMemo(
            et.getContractNumber(), "중도상환");
        voucherRepository.deleteAll(oldVouchers);
      }
      createReturnVoucher(et);
    } else if (!wasReturnCompleted && isNowReturnCompleted) {
      // 처음으로 전액상환+처리완료 상태가 된 경우
      createReturnVoucher(et);
    } else if (wasReturnCompleted && !isNowReturnCompleted) {
      // NEW-BUG-05: 처리완료 → 처리대기 등 취소 시 기존 전표 삭제
      if (et.getContractNumber() != null) {
        List<Voucher> oldVouchers = voucherRepository.findByContractNumberAndMemo(
            et.getContractNumber(), "중도상환");
        voucherRepository.deleteAll(oldVouchers);
      }
    }

    // 채권 상태 동기화 — 전액상환 처리완료만 완제(종료)로 본다.
    if (isNowCompleted && isNowReturnCompleted) {
      updateContractStatus(et.getContractNumber(), ContractStatus.CLOSED);
    } else if (wasCompleted && wasReturnCompleted) {
      // 처리완료 -> 처리대기로 되돌린 경우 완제를 취소한다.
      updateContractStatus(et.getContractNumber(), ContractStatus.NORMAL);
    }
  }

  public void delete(Long id) {
    EarlyTermination et = earlyTerminationRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("중도상환 데이터를 찾을 수 없습니다. id=" + id));

    // BUG-06: 연관 전표(중도상환) 삭제
    if (et.getContractNumber() != null) {
      List<Voucher> vouchers = voucherRepository.findByContractNumberAndMemo(
          et.getContractNumber(), "중도상환");
      if (!vouchers.isEmpty()) {
        voucherRepository.deleteAll(vouchers);
      }
    }

    // 해지대기 상태였던 계약은 진행중으로 복구 (처리완료로 완전히 해지된 경우는 복구 안 함)
    if (et.getContractNumber() != null) {
      contractRepository.findByContractNumber(et.getContractNumber()).ifPresent(contract -> {
        if ("해지대기".equals(contract.getStatus())) {
          contract.setStatus("진행중");
          contractRepository.save(contract);
          log.info("중도상환 삭제로 계약 상태 복구: {} 해지대기→진행중", et.getContractNumber());
        }
      });
    }

    earlyTerminationRepository.deleteById(id);
  }

  @Transactional(readOnly = true)
  public ContractLookupResponse lookupContract(String contractNumber) {
    Contract contract = contractRepository.findByContractNumber(contractNumber)
        .orElseThrow(() -> new IllegalArgumentException("계약번호를 찾을 수 없습니다: " + contractNumber));

    return ContractLookupResponse.builder()
        .contractId(contract.getId())
        .contractNumber(contract.getContractNumber())
        .customerName(resolveCustomerName(contract))
        .contractType(contract.getLoanType())
        .startDate(contract.getStartDate())
        .endDate(contract.getEndDate())
        .monthlyRent(contract.getMonthlyPayment())
        .totalRent(contract.getLoanAmount())
        .build();
  }

  @Transactional(readOnly = true)
  public List<ContractSearchRowDto> searchContracts(String kw) {
    String q = (kw == null) ? "" : kw.trim();
    List<Contract> list = contractRepository.searchTop200(q);

    return list.stream().map(c -> ContractSearchRowDto.builder()
        .contractId(c.getId())
        .contractNumber(c.getContractNumber())
        .customerName(resolveCustomerName(c))
        .contractType(c.getLoanType())
        .startDate(c.getStartDate())
        .endDate(c.getEndDate())
        .monthlyRent(c.getMonthlyPayment())
        .totalRent(c.getLoanAmount())
        .build()
    ).toList();
  }

  private EarlyTerminationRowDto toRowDto(EarlyTermination e) {
    return EarlyTerminationRowDto.builder()
        .id(e.getId())
        .contractNumber(e.getContractNumber())
        .customerName(e.getCustomerName())
        .terminationMethod(e.getTerminationMethod())
        .terminationDate(e.getTerminationDate())
        .terminationAmount(e.getTerminationAmount())
        .uncollectedRent(e.getUncollectedRent())
        .totalAmount(e.getTotalAmount())
        .status(e.getStatus())
        .build();
  }

  private EarlyTerminationDetailResponse toDetailDto(EarlyTermination e) {
    return EarlyTerminationDetailResponse.builder()
        .id(e.getId())
        .contractId(e.getContractId())
        .contractNumber(e.getContractNumber())
        .customerName(e.getCustomerName())
        .contractType(e.getContractType())
        .startDate(e.getStartDate())
        .endDate(e.getEndDate())
        .monthlyRent(e.getMonthlyRent())
        .totalRent(e.getTotalRent())
        .terminationMethod(e.getTerminationMethod())
        .terminationDate(e.getTerminationDate())
        .status(e.getStatus())
        .terminationAmount(e.getTerminationAmount())
        .uncollectedRent(e.getUncollectedRent())
        .terminationFee(e.getTerminationFee())
        .totalAmount(e.getTotalAmount())
        .build();
  }

  private String resolveCustomerName(Contract contract) {
    String customerNumber = contract.getCustomerNumber();
    if (customerNumber == null || customerNumber.isBlank()) return "-";

    Customer c = customerRepository.findByCustomerNumber(customerNumber).orElse(null);
    return (c != null) ? c.getCustomerName() : "-";
  }

  private boolean isReturnCompleted(EarlyTermination et) {
    return "전액상환".equals(et.getTerminationMethod()) && "처리완료".equals(et.getStatus());
  }

  /**
   * 중도해지 > 반납 > 처리완료 시 전표 발생
   * 기타계정관리 earlyTermMapping 설정 기준으로 각 분개 항목의 차변/대변 계정을 결정한다.
   * 금액이 0인 항목과 계정이 미설정된 항목은 warn 로그 후 해당 분개를 건너뛴다.
   * 최종적으로 유효한 분개가 하나도 없으면 전표를 생성하지 않는다.
   */
  private void createReturnVoucher(EarlyTermination et) {
    long uncollectedRent   = safe(et.getUncollectedRent());
    long terminationAmount = safe(et.getTerminationAmount());
    long terminationFee    = safe(et.getTerminationFee());

    String unrDebit      = accountSettings.getEarlyTermUnrealizedRentDebit();
    String unrCredit     = accountSettings.getEarlyTermUnrealizedRentCredit();
    String amtDebit  = accountSettings.getEarlyTermAmountDebit();
    // BUG-9차-02: 미회수렌트료(uncollectedRent)가 0이면 미수금 잔액이 없는 상태이므로
    // 별도 대변 계정(creditNoReceivable)을 사용한다. 미설정 시 기본 credit 계정으로 fallback.
    String amtCredit;
    if (uncollectedRent == 0) {
      String noRecCredit = accountSettings.getEarlyTermAmountCreditNoReceivableAccount();
      amtCredit = (noRecCredit != null) ? noRecCredit : accountSettings.getEarlyTermAmountCredit();
    } else {
      amtCredit = accountSettings.getEarlyTermAmountCredit();
    }
    String feeDebit  = accountSettings.getEarlyTermFeeDebit();
    String feeCredit = accountSettings.getEarlyTermFeeCredit();

    List<VoucherCreateRequest.VoucherLineRequest> debitEntries  = new ArrayList<>();
    List<VoucherCreateRequest.VoucherLineRequest> creditEntries = new ArrayList<>();

    // 미회수렌트료 분개
    if (uncollectedRent > 0) {
      if (unrDebit == null || unrCredit == null) {
        log.warn("중도해지 미회수렌트료 분개 생략: 기타계정관리 > 중도해지 > 미회수렌트료 차변/대변을 설정해주세요. etId={}", et.getId());
      } else {
        // 차변(미수금)은 항상 총액
        debitEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .account(unrDebit).amount(uncollectedRent).description("미회수렌트료").build());
        // 대변: 면세사업(대부업)이므로 부가세 분리 없이 총액으로 계상
        creditEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .account(unrCredit).amount(uncollectedRent).description("미회수렌트료").build());
      }
    }

    // 계좌 정보 suffix 공통
    String acctSuffix = (et.getCompanyAccount() != null && !et.getCompanyAccount().isBlank())
        ? " [계좌: " + et.getCompanyAccount() + "]" : "";

    // 중도상환금액 분개
    // BUG-06 수정: 미수금(amtCredit) 크레딧이 미수금 데빗(uncollectedRent)을 초과하면 순액이
    // 음수가 됨. → 미수금 크레딧은 uncollectedRent 이내로 제한하고, 초과분은
    // noReceivableAccount(선수금 등)로 분리 처리한다.
    if (terminationAmount > 0) {
      if (amtDebit == null) {
        log.warn("중도해지 상환금액 분개 생략: 기타계정관리 > 중도해지 > 중도상환금액 차변을 설정해주세요. etId={}", et.getId());
      } else {
        debitEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .account(amtDebit).amount(terminationAmount).description("중도상환금액" + acctSuffix).build());

        // 미수금으로 상계 가능한 금액은 실제 미수금(uncollectedRent) 이내
        long creditToReceivable = (uncollectedRent > 0) ? Math.min(terminationAmount, uncollectedRent) : 0L;
        long creditToOther      = terminationAmount - creditToReceivable;

        if (creditToReceivable > 0) {
          if (amtCredit == null) {
            log.warn("중도해지 상환금액 분개 생략(대변): 기타계정관리 > 중도해지 > 중도상환금액 대변을 설정해주세요. etId={}", et.getId());
            debitEntries.remove(debitEntries.size() - 1); // 위에서 추가한 차변도 제거
          } else {
            creditEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
                .account(amtCredit).amount(creditToReceivable).description("중도상환금액").build());
          }
        }
        if (creditToOther > 0) {
          String noRecCredit = accountSettings.getEarlyTermAmountCreditNoReceivableAccount();
          String otherAccount = (noRecCredit != null) ? noRecCredit : amtCredit;
          if (otherAccount == null) {
            log.warn("중도해지 상환금액 초과분 대변 계정 미설정. 기타계정관리 > 중도해지 > 중도상환금액 대변(미수금없음)을 설정해주세요. etId={}", et.getId());
          } else {
            creditEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
                .account(otherAccount).amount(creditToOther).description("중도상환금액(초과분)").build());
          }
        }
      }
    }

    // 중도상환수수료 분개
    if (terminationFee > 0) {
      if (feeDebit == null || feeCredit == null) {
        log.warn("중도해지 수수료 분개 생략: 기타계정관리 > 중도해지 > 중도상환수수료 차변/대변을 설정해주세요. etId={}", et.getId());
      } else {
        debitEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .account(feeDebit).amount(terminationFee).description("중도상환수수료" + acctSuffix).build());
        creditEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .account(feeCredit).amount(terminationFee).description("중도상환수수료").build());
      }
    }

    if (debitEntries.isEmpty()) {
      log.warn("중도해지 전표 생략: 유효한 분개 항목이 없습니다. 기타계정관리에서 earlyTermMapping을 설정해주세요. etId={}", et.getId());
      return;
    }

    voucherService.create(
        VoucherCreateRequest.builder()
            .voucherDate(et.getTerminationDate() != null ? et.getTerminationDate() : LocalDate.now())
            .contractNumber(et.getContractNumber())
            .memo("중도상환")
            .debitEntries(debitEntries)
            .creditEntries(creditEntries)
            .build()
    );
  }

  /**
   * NEW-07: 중도해지 처리완료 시 연관 계약의 status를 갱신한다.
   */
  private void updateContractStatus(String contractNumber, String newStatus) {
    if (contractNumber == null || contractNumber.isBlank()) return;
    contractRepository.findByContractNumber(contractNumber).ifPresent(contract -> {
      contract.setStatus(newStatus);
      contractRepository.save(contract);
    });
  }

  private long safe(Long v) {
    return (v == null) ? 0L : v;
  }
}