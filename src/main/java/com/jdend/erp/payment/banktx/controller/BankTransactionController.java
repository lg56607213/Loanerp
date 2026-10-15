package com.jdend.erp.payment.banktx.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.payment.banktx.dto.*;
import com.jdend.erp.payment.banktx.service.BankTransactionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bank-transactions")
public class BankTransactionController {

  private final BankTransactionService service;
  private final PermissionService permissionService;

  @GetMapping
  public List<BankTransactionRowResponse> search(
      @RequestParam(value="bank", required=false, defaultValue="") String bank,
      @RequestParam(value="accountNo", required=false, defaultValue="") String accountNo,
      @RequestParam(value="startDate", required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam(value="endDate", required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
  ) {
    return service.search(bank, accountNo, startDate, endDate);
  }

  @GetMapping("/template")
  public ResponseEntity<byte[]> template() {
    byte[] bytes = service.template();
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"bank_transaction_template.xlsx\"")
        .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        .body(bytes);
  }

  /**
   * @param dryRun true 면 검증만 하고 저장하지 않는다. 화면에서 무엇이 빠지는지
   *               먼저 보여준 뒤, 사용자가 확인하면 false 로 다시 호출한다.
   */
  @PostMapping("/upload")
  public UploadResultResponse upload(
      @RequestParam("bankName") String bankName,
      @RequestParam("accountNo") String accountNo,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun
  ) {
    return service.uploadExcel(bankName, accountNo, file, dryRun);
  }

  @PatchMapping("/remarks")
  public void updateRemarks(@RequestBody List<RemarksUpdateRequest> list) {
    service.updateRemarksBulk(list);
  }

  /**
   * 고른 내역 삭제. 지운 건수를 돌려준다.
   *
   * 지우면 일자별 입출금현황·자금일보의 은행 숫자에서도 함께 빠지므로
   * 책임자 권한을 요구한다(수정·삭제에 대한 이 ERP 의 공통 규칙).
   */
  @DeleteMapping
  public Map<String, Object> delete(@RequestBody BankTransactionDeleteRequest req, HttpSession session) {
    permissionService.requireManager(session);
    return Map.of("deletedRows", service.deleteByIds(req.getIds()));
  }

  @GetMapping("/distinct-accounts")
  public List<BankAccountPickRowResponse> distinctAccounts(
      @RequestParam(value="kw", required=false, defaultValue="") String kw
  ) {
    return service.distinctAccounts(kw);
  }
}
