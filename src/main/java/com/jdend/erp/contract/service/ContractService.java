package com.jdend.erp.contract.service;

import com.jdend.erp.contract.dto.*;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.customer.CustomerRepository;
import com.jdend.erp.payment.schedule.service.PaymentScheduleAutoGeneratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ContractService {

  private final ContractRepository contractRepo;
  private final CustomerRepository customerRepo;
  private final PaymentScheduleAutoGeneratorService scheduleAutoGen;

  @Transactional(readOnly = true)
  public List<ContractResponse> list() {
    List<Contract> list = contractRepo.findAll();
    list.sort(Comparator.comparing(Contract::getId).reversed());
    return list.stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public ContractResponse detail(Long id) {
    Contract c = contractRepo.findById(id)
        .orElseThrow(() -> new RuntimeException("계약 없음 id=" + id));
    return toResponse(c);
  }

  @Transactional(readOnly = true)
  public ContractFullResponse detailFullByNumber(String contractNumber) {
    if (contractNumber == null || contractNumber.isBlank()) {
      throw new RuntimeException("계약번호(contractNumber) 필수");
    }

    Contract c = contractRepo.findWithCustomerByContractNumber(contractNumber.trim())
        .orElseThrow(() -> new RuntimeException("계약 없음 contractNumber=" + contractNumber));

    return toFullResponse(c);
  }

  @Transactional(readOnly = true)
  public ContractFullResponse detailFull(Long id) {
    Contract c = contractRepo.findById(id)
        .orElseThrow(() -> new RuntimeException("계약 없음 id=" + id));
    return toFullResponse(c);
  }

  /**
   * 계약번호 미리보기. contractType 미지정 시 "장기" 기준으로 채번한다.
   */
  public String nextNumberPreview(String contractType) {
    return generateNextContractNumber(contractType != null ? contractType : "장기");
  }

  /**
   * 신규 계약번호 채번.
   * - 장기: LTC + YYMMDD(6) + 순번(3) + 회차(3) = 15자리
   * - 단기: STC + YYMMDD(6) + 순번(3) + 회차(3) = 15자리
   * 신규 계약의 회차는 항상 001. 재렌트는 {@link #generateRerentContractNumber} 사용.
   */
  public synchronized String generateNextContractNumber(String contractType) {
    String prefix = "장기".equals(contractType) ? "LTC" : "STC";
    String yymmdd = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
    String datePrefix = prefix + yymmdd; // 예: LTC260804

    Optional<String> maxOpt = contractRepo.findMaxContractNumberByPrefix(datePrefix);

    int nextSeq = 1;
    if (maxOpt.isPresent()) {
      String max = maxOpt.get(); // 예: LTC260804003002
      try {
        // LTC(3)+YYMMDD(6) = 9자리 이후 순번(3자리)
        String seqStr = max.substring(9, 12);
        nextSeq = Integer.parseInt(seqStr) + 1;
      } catch (Exception ignored) {}
    }

    // 신규 계약: 회차 001
    return String.format("%s%03d001", datePrefix, nextSeq);
  }

  /**
   * 재렌트 계약번호 채번. 기존 계약번호의 base(앞 12자리)를 유지하고 회차만 증가.
   * 예) LTC260804001001 → LTC260804001002
   */
  public synchronized String generateRerentContractNumber(String existingContractNo) {
    if (existingContractNo == null || existingContractNo.length() < 12) {
      throw new IllegalArgumentException("기존 계약번호가 올바르지 않습니다: " + existingContractNo);
    }
    // base = prefix(3) + YYMMDD(6) + 순번(3) = 앞 12자리
    String base = existingContractNo.substring(0, 12);

    Optional<String> maxOpt = contractRepo.findMaxContractNumberByPrefix(base);

    int nextRound = 1;
    if (maxOpt.isPresent()) {
      String max = maxOpt.get(); // 예: LTC260804001001
      try {
        // 마지막 3자리가 회차
        String roundStr = max.substring(12, 15);
        nextRound = Integer.parseInt(roundStr) + 1;
      } catch (Exception ignored) {}
    }

    return String.format("%s%03d", base, nextRound);
  }

  @Transactional
  public ContractResponse create(ContractRequest req) {
    validateRequired(req);

    Customer customer = customerRepo.findByCustomerNumber(req.customerNumber).orElse(null);

    Long monthlyRent = nvl(req.monthlyRent);
    Integer billingCount = (req.billingCount == null ? 0 : req.billingCount);
    Long totalRent = (req.totalRent != null ? req.totalRent : monthlyRent * billingCount);

    Contract c = Contract.builder()
        .contractNumber(generateNextContractNumber(req.contractType))
        .customer(customer)
        .customerNumber(req.customerNumber)
        .vehicleNo(req.vehicleNo)
        .vehicleModel(req.vehicleModel)
        .contractType(req.contractType)
        .contractCategory(req.contractCategory)
        .status(normalizeStatus(req.status))
        .startDate(req.startDate)
        .endDate(req.endDate)
        .taxInvoiceDay(req.taxInvoiceDay)
        .paymentDueDay(req.paymentDueDay)
        .advancePayment(nvl(req.advancePayment))
        .monthlyRent(monthlyRent)
        .billingDay(req.billingDay)
        .billingCount(billingCount)
        .totalRent(totalRent)
        .deposit(nvl(req.deposit))
        .maturityOption(req.maturityOption)
        .residualValue(nvl(req.residualValue))
        .vehicleInsurance(req.vehicleInsurance)
        .insuranceAge(req.insuranceAge)
        .vehicleInsuranceLimit(req.vehicleInsuranceLimit)
        .vehicleDeductible(req.vehicleDeductible)
        .propertyLiability(req.propertyLiability)
        .propertyDeductible(req.propertyDeductible)
        .personalDeductible(req.personalDeductible)
        .passengerDeductible(req.passengerDeductible)
        .remarks(req.remarks)
        .build();

    if (c.getBillingCount() == null) c.setBillingCount(0);
    if (c.getTotalRent() == null) c.setTotalRent(0L);
    if (c.getAdvancePayment() == null) c.setAdvancePayment(0L);
    if (c.getDeposit() == null) c.setDeposit(0L);
    if (c.getResidualValue() == null) c.setResidualValue(0L);

    contractRepo.save(c);
    scheduleAutoGen.ensureGenerated(c);

    return toResponse(c);
  }

  @Transactional
  public ContractResponse update(Long id, ContractRequest req) {
    Contract c = contractRepo.findById(id)
        .orElseThrow(() -> new RuntimeException("계약 없음 id=" + id));

    // BUG-05: 월세 변경 여부를 수정 전에 기록한다
    Long prevMonthlyRent = c.getMonthlyRent();
    Integer prevBillingCount = c.getBillingCount();
    LocalDate prevStartDate = c.getStartDate();

    if (req.customerNumber != null && !req.customerNumber.isBlank()) {
      c.setCustomerNumber(req.customerNumber);
      c.setCustomer(customerRepo.findByCustomerNumber(req.customerNumber).orElse(null));
    }

    if (req.vehicleNo != null && !req.vehicleNo.isBlank()) {
      c.setVehicleNo(req.vehicleNo);
    }

    if (req.vehicleModel != null) c.setVehicleModel(req.vehicleModel);

    if (req.contractType != null && !req.contractType.isBlank()) c.setContractType(req.contractType);
    if (req.contractCategory != null && !req.contractCategory.isBlank()) c.setContractCategory(req.contractCategory);
    if (req.status != null && !req.status.isBlank()) c.setStatus(req.status.trim());

    if (req.startDate != null) c.setStartDate(req.startDate);
    if (req.endDate != null) c.setEndDate(req.endDate);

    c.setTaxInvoiceDay(req.taxInvoiceDay);
    c.setPaymentDueDay(req.paymentDueDay);

    if (req.billingCount == null || req.billingCount <= 0) {
      throw new RuntimeException("청구횟수는 1 이상이어야 합니다. 청구횟수가 0이면 청구 스케줄이 생성되지 않아 청구생성에서 누락됩니다.");
    }

    c.setAdvancePayment(nvl(req.advancePayment));
    c.setMonthlyRent(nvl(req.monthlyRent));
    c.setBillingDay(req.billingDay);
    c.setBillingCount(req.billingCount);
    c.setTotalRent(req.totalRent != null ? req.totalRent : c.getMonthlyRent() * c.getBillingCount());

    c.setDeposit(nvl(req.deposit));
    c.setMaturityOption(req.maturityOption);
    c.setResidualValue(nvl(req.residualValue));

    c.setVehicleInsurance(req.vehicleInsurance);
    c.setInsuranceAge(req.insuranceAge);
    c.setVehicleInsuranceLimit(req.vehicleInsuranceLimit);
    c.setVehicleDeductible(req.vehicleDeductible);

    c.setPropertyLiability(req.propertyLiability);
    c.setPropertyDeductible(req.propertyDeductible);
    c.setPersonalDeductible(req.personalDeductible);
    c.setPassengerDeductible(req.passengerDeductible);

    c.setRemarks(req.remarks);

    contractRepo.save(c);

    // BUG-05: 월세·청구횟수·시작일이 변경된 경우 미래 스케줄을 삭제하고 재생성
    boolean scheduleKeyChanged =
        !nvl(prevMonthlyRent).equals(nvl(c.getMonthlyRent()))
        || !safeEq(prevBillingCount, c.getBillingCount())
        || !safeEq(prevStartDate, c.getStartDate());

    if (scheduleKeyChanged) {
      scheduleAutoGen.regenerate(c);
    } else {
      scheduleAutoGen.ensureGenerated(c);
    }

    return toResponse(c);
  }

  @Transactional
  public void delete(Long id) {
    if (!contractRepo.existsById(id)) {
      throw new RuntimeException("계약 없음 id=" + id);
    }
    contractRepo.deleteById(id);
  }

  private void validateRequired(ContractRequest req) {
    if (req.customerNumber == null || req.customerNumber.isBlank()) throw new RuntimeException("고객번호(customerNumber) 필수");
    if (req.vehicleNo == null || req.vehicleNo.isBlank()) throw new RuntimeException("차량번호(vehicleNo) 필수");
    if (req.contractType == null || req.contractType.isBlank()) throw new RuntimeException("계약구분 필수");
    if (req.contractCategory == null || req.contractCategory.isBlank()) throw new RuntimeException("계약유형 필수");
    if (req.startDate == null) throw new RuntimeException("계약시작일 필수");
    if (req.endDate == null) throw new RuntimeException("계약종료일 필수");
    // BUG-⑨ 수정: 구 계약 이관을 위해 endDate < today 차단 조건 제거.
    // startDate > endDate 검증만 유지한다.
    if (req.startDate != null && req.endDate.isBefore(req.startDate)) {
      throw new IllegalArgumentException("계약 종료일은 시작일보다 이전일 수 없습니다.");
    }
    if (req.billingCount == null || req.billingCount <= 0) {
      throw new RuntimeException("청구횟수는 1 이상이어야 합니다. 청구횟수가 0이면 청구 스케줄이 생성되지 않아 청구생성에서 누락됩니다.");
    }
  }

  private Long nvl(Long v) {
    return v == null ? 0L : v;
  }

  /** null-safe 동등비교 */
  private <T> boolean safeEq(T a, T b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    return a.equals(b);
  }

  private String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return "대기";
    }
    return status.trim();
  }

  private ContractResponse toResponse(Contract c) {
    String customerName = null;
    if (c.getCustomer() != null) {
      customerName = c.getCustomer().getCustomerName();
    }

    return ContractResponse.builder()
        .id(c.getId())
        .contractNumber(c.getContractNumber())
        .customerNumber(c.getCustomerNumber())
        .customerName(customerName)
        .vehicleNo(c.getVehicleNo())
        .vehicleModel(c.getVehicleModel())
        .contractType(c.getContractType())
        .contractCategory(c.getContractCategory())
        .status(c.getStatus())
        .startDate(c.getStartDate())
        .endDate(c.getEndDate())
        .billingCount(c.getBillingCount())
        .monthlyRent(c.getMonthlyRent())
        .build();
  }

  private ContractFullResponse toFullResponse(Contract c) {
    String customerName = null;
    String customerPhone = null;
    String customerAddress = null;
    String customerRegNo = null;

    if (c.getCustomer() != null) {
      customerName = c.getCustomer().getCustomerName();
      customerPhone = c.getCustomer().getPhone();
      customerAddress = c.getCustomer().getAddress();
      customerRegNo = c.getCustomer().getRegistrationNumber();
    }

    return ContractFullResponse.builder()
        .id(c.getId())
        .contractNumber(c.getContractNumber())
        .customerNumber(c.getCustomerNumber())
        .customerName(customerName)
        .customerPhone(customerPhone)
        .customerAddress(customerAddress)
        .customerRegistrationNumber(customerRegNo)
        .vehicleNo(c.getVehicleNo())
        .vehicleModel(c.getVehicleModel())
        .contractType(c.getContractType())
        .contractCategory(c.getContractCategory())
        .status(c.getStatus())
        .startDate(c.getStartDate())
        .endDate(c.getEndDate())
        .taxInvoiceDay(c.getTaxInvoiceDay())
        .paymentDueDay(c.getPaymentDueDay())
        .advancePayment(nvl(c.getAdvancePayment()))
        .monthlyRent(nvl(c.getMonthlyRent()))
        .billingDay(c.getBillingDay())
        .billingCount(c.getBillingCount() == null ? 0 : c.getBillingCount())
        .totalRent(nvl(c.getTotalRent()))
        .deposit(nvl(c.getDeposit()))
        .maturityOption(c.getMaturityOption())
        .residualValue(nvl(c.getResidualValue()))
        .vehicleInsurance(c.getVehicleInsurance())
        .insuranceAge(c.getInsuranceAge())
        .vehicleInsuranceLimit(c.getVehicleInsuranceLimit())
        .vehicleDeductible(c.getVehicleDeductible())
        .propertyLiability(c.getPropertyLiability())
        .propertyDeductible(c.getPropertyDeductible())
        .personalDeductible(c.getPersonalDeductible())
        .passengerDeductible(c.getPassengerDeductible())
        .remarks(c.getRemarks())
        .build();
  }
}