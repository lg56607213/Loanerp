package com.jdend.erp.payment.consultation.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.payment.consultation.dto.*;
import com.jdend.erp.payment.consultation.entity.Consultation;
import com.jdend.erp.payment.consultation.repository.ConsultationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ConsultationService {

  private final ContractRepository contractRepo;
  private final ConsultationRepository consultRepo;

  @Transactional(readOnly = true)
  public ContractSummaryResponse contractSummary(String contractNumber) {
    String cn = safe(contractNumber);
    if (cn.isBlank()) throw new RuntimeException("계약번호는 필수입니다.");

    Contract c = contractRepo.findWithCustomerByContractNumber(cn)
        .orElseThrow(() -> new RuntimeException("계약 없음: " + cn));

    Customer cu = c.getCustomer();

    return ContractSummaryResponse.builder()
        .contractNumber(c.getContractNumber())
        .customerName(cu != null ? cu.getCustomerName() : null)
        .build();
  }

  public ConsultationResponse create(ConsultationCreateRequest req) {
    String cn = safe(req.getContractNumber());
    if (cn.isBlank()) throw new RuntimeException("계약번호는 필수입니다.");

    Contract c = contractRepo.findByContractNumber(cn)
        .orElseThrow(() -> new RuntimeException("계약 없음: " + cn));

    LocalDate d = (req.getConsultDate() != null) ? req.getConsultDate() : LocalDate.now();
    String content = safe(req.getConsultContent());
    if (content.isBlank()) throw new RuntimeException("상담내용은 필수입니다.");

    Consultation saved = consultRepo.save(
        Consultation.builder()
            .contract(c)
            .contractNumber(c.getContractNumber())
            .consultDate(d)
            .consultContent(content)
            .build()
    );

    return toRes(saved);
  }

  @Transactional(readOnly = true)
  public List<ConsultationResponse> history(String contractNumber) {
    String cn = safe(contractNumber);
    if (cn.isBlank()) throw new RuntimeException("계약번호는 필수입니다.");

    return consultRepo.findByContractNumberOrder(cn).stream()
        .map(this::toRes)
        .toList();
  }

  public void delete(Long id) {
    if (!consultRepo.existsById(id)) throw new RuntimeException("상담내역 없음: " + id);
    consultRepo.deleteById(id);
  }

  private ConsultationResponse toRes(Consultation c) {
    return ConsultationResponse.builder()
        .id(c.getId())
        .contractNumber(c.getContractNumber())
        .consultDate(c.getConsultDate())
        .consultContent(c.getConsultContent())
        .createdAt(c.getCreatedAt())
        .build();
  }

  private String safe(String s) { return s == null ? "" : s.trim(); }
}