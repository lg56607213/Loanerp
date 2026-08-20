package com.jdend.erp.contract.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.common.excel.ExcelExportService;
import com.jdend.erp.common.excel.ExcelUploadResultResponse;
import com.jdend.erp.contract.dto.*;
import com.jdend.erp.contract.service.ContractBulkUploadService;
import com.jdend.erp.contract.service.ContractService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/contracts")

public class ContractController {

  private final ContractService service;
  private final ContractBulkUploadService bulkUploadService;
  private final PermissionService permissionService;
  private final ExcelExportService excelExportService;

  @GetMapping
  public List<ContractResponse> list() {
    return service.list();
  }

  /** 채권번호 미리보기 (loanType: 신용대출 / 담보대출 / 사업자대출) */
  @GetMapping("/next-number")
  public NextContractNumberResponse nextNumber(
      @RequestParam(value = "loanType", defaultValue = "신용대출") String loanType) {
    return NextContractNumberResponse.builder()
      .contractNumber(service.nextNumberPreview(loanType))
      .build();
  }

  // ✅✅✅ 계약번호로 full 상세 (프론트가 ?id=R00001002 같은 상황일 때 쓰면 됨)
  // GET /api/contracts/by-number/R00001002/full
  @GetMapping("/by-number/{contractNumber}/full")
  public ContractFullResponse detailFullByNumber(@PathVariable String contractNumber) {
    return service.detailFullByNumber(contractNumber);
  }

  // ✅ full 상세(수정/출력용) - 숫자 id만 받음
  @GetMapping("/{id:\\d+}/full")
  public ContractFullResponse detailFull(@PathVariable Long id) {
    return service.detailFull(id);
  }

  // ✅ 숫자만 id로 받게 제한
  @GetMapping("/{id:\\d+}")
  public ContractResponse detail(@PathVariable Long id) {
    return service.detail(id);
  }

  @PostMapping
  public ContractResponse create(@RequestBody ContractRequest req) {
    return service.create(req);
  }

  @PutMapping("/{id:\\d+}")
  public ContractResponse update(@PathVariable Long id, @RequestBody ContractUpdateRequest req, HttpSession session) {
    permissionService.requireManager(session);
    return service.update(id, req);
  }

  @DeleteMapping("/{id:\\d+}")
  public void delete(@PathVariable Long id, HttpSession session) {
    permissionService.requireManager(session);
    service.delete(id);
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export(
          @RequestParam(required = false) LocalDate startDate,
          @RequestParam(required = false) LocalDate endDate
  ) {
    String[] headers = {"채권번호", "고객번호", "고객명", "대출구분", "대출금", "이자율",
            "상환방식", "상태", "시작일자", "종료일자", "납입일자", "회차수", "월납입액", "잔여원금"};
    List<Object[]> rows = service.list().stream()
            .filter(c -> {
                if (startDate != null && (c.getStartDate() == null || c.getStartDate().isBefore(startDate))) return false;
                if (endDate != null && (c.getStartDate() == null || c.getStartDate().isAfter(endDate))) return false;
                return true;
            })
            .map(c -> new Object[]{
                    c.getContractNumber(), c.getCustomerNumber(), c.getCustomerName(),
                    c.getLoanType(), c.getLoanAmount(), c.getInterestRate(),
                    c.getRepaymentMethod(), c.getStatus(),
                    c.getStartDate(), c.getEndDate(), c.getPaymentDay(),
                    c.getInstallmentCount(), c.getMonthlyPayment(), c.getRemainingPrincipal()
            }).collect(Collectors.toList());
    byte[] data = excelExportService.build("채권목록", headers, rows);
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''loans.xlsx")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(data);
  }

  @GetMapping("/bulk-upload/template")
  public ResponseEntity<byte[]> bulkUploadTemplate() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=loan_template.xlsx")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(bulkUploadService.template());
  }

  @PostMapping("/bulk-upload")
  public ExcelUploadResultResponse bulkUpload(@RequestParam("file") MultipartFile file) {
    return bulkUploadService.upload(file);
  }
}