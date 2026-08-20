package com.jdend.erp.payment.receivable.service;

import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.payment.receivable.dto.*;
import com.jdend.erp.payment.receivable.entity.Receivable;
import com.jdend.erp.payment.receivable.repository.ReceivableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ReceivableService {

  private final ReceivableRepository receivableRepo;
  private final ContractRepository contractRepo;

  public ReceivableResponse create(ReceivableCreateRequest req) {
    String cn = safe(req.getContractNumber());
    if (cn.isBlank()) throw new RuntimeException("계약번호는 필수입니다.");

    Contract c = contractRepo.findWithCustomerByContractNumber(cn)
        .orElseThrow(() -> new RuntimeException("계약 없음: " + cn));

    Customer cu = c.getCustomer();

    Receivable r = Receivable.builder()
        .contract(c)
        .contractNumber(c.getContractNumber())
        .customerName(cu != null ? cu.getCustomerName() : null)

        .receivableAmount(nvl(req.getReceivableAmount()))
        .receivableDate(req.getReceivableDate() != null ? req.getReceivableDate() : LocalDate.now())
        .receivableType(req.getReceivableType())
        .content(safe(req.getContent()))
        .status((req.getStatus() == null || req.getStatus().isBlank()) ? "미납" : req.getStatus())
        .build();

    if (r.getContent().isBlank()) throw new RuntimeException("내용은 필수입니다.");

    Receivable saved = receivableRepo.save(r);
    return toResponse(saved);
  }

  @Transactional(readOnly = true)
  public List<ReceivableResponse> list(LocalDate startDate, LocalDate endDate, String customerName, String status) {
    List<Receivable> list = receivableRepo.search(startDate, endDate, safe(customerName), safe(status));
    return list.stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public ReceivableResponse detail(Long id) {
    Receivable r = receivableRepo.findById(id)
        .orElseThrow(() -> new RuntimeException("미수 없음: " + id));
    return toResponse(r);
  }

  public ReceivableResponse update(Long id, ReceivableUpdateRequest req) {
    Receivable r = receivableRepo.findById(id)
        .orElseThrow(() -> new RuntimeException("미수 없음: " + id));

    if (req.getReceivableAmount() != null) r.setReceivableAmount(req.getReceivableAmount());
    if (req.getReceivableDate() != null) r.setReceivableDate(req.getReceivableDate());
    if (req.getReceivableType() != null) r.setReceivableType(req.getReceivableType());
    if (req.getContent() != null) r.setContent(req.getContent());
    if (req.getStatus() != null && !req.getStatus().isBlank()) r.setStatus(req.getStatus());

    if (r.getContent() == null || r.getContent().isBlank()) {
      throw new RuntimeException("내용은 필수입니다.");
    }

    return toResponse(r);
  }

  public void delete(Long id) {
    if (!receivableRepo.existsById(id)) throw new RuntimeException("미수 없음: " + id);
    receivableRepo.deleteById(id);
  }

  /** 상태만 변경 — 전표 발생 없음 (분개는 일일전표에서 수기 처리) */
  public void updateStatus(Long id, String status) {
    if (status == null || status.isBlank()) throw new RuntimeException("상태값이 필요합니다.");
    Receivable r = receivableRepo.findById(id)
        .orElseThrow(() -> new RuntimeException("미수 없음: " + id));
    r.setStatus(status);
  }

  private ReceivableResponse toResponse(Receivable r) {
    return ReceivableResponse.builder()
        .id(r.getId())
        .contractId(r.getContract() != null ? r.getContract().getId() : null)
        .contractNumber(r.getContractNumber())
        .customerName(r.getCustomerName())
        .receivableAmount(r.getReceivableAmount())
        .receivableDate(r.getReceivableDate())
        .receivableType(r.getReceivableType())
        .content(r.getContent())
        .status(r.getStatus())
        .build();
  }

  private String safe(String s) { return s == null ? "" : s.trim(); }
  private Long nvl(Long v) { return v == null ? 0L : v; }
}