package com.jdend.erp.contract.maturitytermination.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.maturitytermination.dto.*;
import com.jdend.erp.contract.maturitytermination.entity.MaturityTermination;
import com.jdend.erp.contract.maturitytermination.repository.MaturityTerminationRepository;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.customer.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MaturityTerminationService {

  private final MaturityTerminationRepository repo;
  private final ContractRepository contractRepository;
  private final CustomerRepository customerRepository;

  @Transactional(readOnly = true)
  public Page<MaturityTerminationRowDto> list(String status, int page, int size) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "id"));

    Page<MaturityTermination> p;
    if (status == null || status.isBlank() || "all".equalsIgnoreCase(status) || "전체".equals(status)) {
      p = repo.findAll(pageable);
    } else {
      p = repo.findByStatus(status, pageable);
    }

    return p.map(this::toRow);
  }

  @Transactional(readOnly = true)
  public MaturityTerminationDetailResponse get(Long id) {
    MaturityTermination t = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("만기종료 데이터를 찾을 수 없습니다. id=" + id));
    return toDetail(t);
  }

  public Long create(MaturityTerminationCreateRequest req) {
    Contract c = contractRepository.findByContractNumber(req.getContractNumber())
        .orElseThrow(() -> new IllegalArgumentException("계약번호를 찾을 수 없습니다: " + req.getContractNumber()));

    String customerName = resolveCustomerName(c);

    MaturityTermination t = MaturityTermination.builder()
        .contractId(c.getId())
        .contractNumber(c.getContractNumber())
        .customerName(customerName)
        .contractType(c.getLoanType())
        .startDate(c.getStartDate())
        .endDate(c.getEndDate())
        .monthlyRent(safe(c.getMonthlyPayment()))
        .terminationMethod(req.getTerminationMethod())
        .terminationDate(req.getTerminationDate())
        .unpaidAmount(safe(req.getUnpaidAmount()))
        .status(req.getStatus())
        .build();

    MaturityTermination saved = repo.save(t);

    // NEW-07: 등록 시 종료완료이면 연관 계약 상태를 "만기종료"로 갱신
    if ("종료완료".equals(saved.getStatus())) {
      updateContractStatus(saved.getContractNumber(), "만기종료");
    }

    return saved.getId();
  }

  public void update(Long id, MaturityTerminationUpdateRequest req) {
    MaturityTermination t = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("만기종료 데이터를 찾을 수 없습니다. id=" + id));

    String prevStatus = t.getStatus(); // NEW-07: 이전 상태 저장

    t.setTerminationMethod(req.getTerminationMethod());
    t.setTerminationDate(req.getTerminationDate());
    t.setUnpaidAmount(safe(req.getUnpaidAmount()));
    t.setStatus(req.getStatus());

    // NEW-07: 종료완료로 전환되는 시점에 연관 계약 상태를 "만기종료"로 갱신
    if (!"종료완료".equals(prevStatus) && "종료완료".equals(req.getStatus())) {
      updateContractStatus(t.getContractNumber(), "만기종료");
    }
  }

  public void delete(Long id) {
    repo.deleteById(id);
  }

  private MaturityTerminationRowDto toRow(MaturityTermination t) {
    return MaturityTerminationRowDto.builder()
        .id(t.getId())
        .contractNumber(t.getContractNumber())
        .customerName(t.getCustomerName())
        .terminationMethod(t.getTerminationMethod())
        .terminationDate(t.getTerminationDate())
        .unpaidAmount(t.getUnpaidAmount())
        .status(t.getStatus())
        .build();
  }

  private MaturityTerminationDetailResponse toDetail(MaturityTermination t) {
    return MaturityTerminationDetailResponse.builder()
        .id(t.getId())
        .contractId(t.getContractId())
        .contractNumber(t.getContractNumber())
        .customerName(t.getCustomerName())
        .contractType(t.getContractType())
        .startDate(t.getStartDate())
        .endDate(t.getEndDate())
        .monthlyRent(t.getMonthlyRent())
        .terminationMethod(t.getTerminationMethod())
        .terminationDate(t.getTerminationDate())
        .unpaidAmount(t.getUnpaidAmount())
        .status(t.getStatus())
        .build();
  }

  private String resolveCustomerName(Contract contract) {
    String customerNumber = contract.getCustomerNumber();
    if (customerNumber == null || customerNumber.isBlank()) return "-";
    Customer c = customerRepository.findByCustomerNumber(customerNumber).orElse(null);
    return (c != null) ? c.getCustomerName() : "-";
  }

  /**
   * NEW-07: 만기종료 처리완료 시 연관 계약의 status를 갱신한다.
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