package com.jdend.erp.payment.banktx.service;

import com.jdend.erp.common.excel.ExcelReader;
import com.jdend.erp.common.excel.ExcelTemplateWriter;
import com.jdend.erp.payment.banktx.dto.*;
import com.jdend.erp.payment.banktx.entity.BankTransaction;
import com.jdend.erp.payment.banktx.repository.PaymentBankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class BankTransactionService {

  private final PaymentBankTransactionRepository repo;

  private static final List<String> EXCEL_HEADERS  = List.of("일자", "입금액", "출금액", "잔액", "적요");
  private static final List<String> EXCEL_SAMPLE   = List.of("2026-01-01", "1000000", "", "5000000", "이자수납");

  @Transactional(readOnly = true)
  public List<BankTransactionRowResponse> search(String bank, String accountNo, LocalDate startDate, LocalDate endDate) {
    return repo.search(safe(bank), safe(accountNo), startDate, endDate).stream()
        .map(this::toRow)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<BankAccountPickRowResponse> distinctAccounts(String kw) {
    List<Object[]> rows = repo.distinctAccounts(safe(kw));
    List<BankAccountPickRowResponse> out = new ArrayList<>();
    for (Object[] r : rows) {
      out.add(BankAccountPickRowResponse.builder()
          .bankName((String) r[0])
          .accountNo((String) r[1])
          .build());
    }
    return out;
  }

  public byte[] template() {
    return ExcelTemplateWriter.write(EXCEL_HEADERS, EXCEL_SAMPLE);
  }

  public UploadResultResponse uploadExcel(String bankName, String accountNo, MultipartFile file) {
    if (file == null || file.isEmpty()) throw new RuntimeException("파일이 비어있습니다.");
    if (isBlank(bankName)) throw new RuntimeException("은행명을 입력하세요.");
    if (isBlank(accountNo)) throw new RuntimeException("계좌번호를 입력하세요.");

    String bn = bankName.trim();
    String an = accountNo.trim();
    String batchId = UUID.randomUUID().toString().replace("-", "");

    List<Map<String, String>> rows;
    try {
      rows = ExcelReader.readRows(file.getInputStream());
    } catch (Exception e) {
      throw new RuntimeException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
    }

    int inserted = 0, skipped = 0;

    for (Map<String, String> row : rows) {
      String dateStr = safe(row.get("일자"));
      if (dateStr.isBlank()) continue;

      LocalDate txDate;
      try {
        txDate = parseDate(dateStr);
      } catch (Exception e) {
        continue;
      }

      long deposit    = parseMoney(row.get("입금액"));
      long withdrawal = parseMoney(row.get("출금액"));
      long balance    = parseMoney(row.get("잔액"));
      String summary  = safe(row.get("적요"));

      String rowHash = sha256(String.join("|", bn, an, txDate.toString(),
          String.valueOf(deposit), String.valueOf(withdrawal), summary));

      if (repo.existsByRowHash(rowHash)) { skipped++; continue; }

      repo.save(BankTransaction.builder()
          .bankName(bn)
          .accountNo(an)
          .txDate(txDate)
          .depositAmount(deposit)
          .withdrawalAmount(withdrawal)
          .balance(balance)
          .summary(summary)
          .importBatchId(batchId)
          .rowHash(rowHash)
          .build());
      inserted++;
    }

    return UploadResultResponse.builder()
        .batchId(batchId)
        .parsedRows(rows.size())
        .insertedRows(inserted)
        .skippedDuplicates(skipped)
        .build();
  }

  public void updateRemarksBulk(List<RemarksUpdateRequest> list) {
    if (list == null || list.isEmpty()) return;
    for (RemarksUpdateRequest req : list) {
      if (req.getId() == null) continue;
      repo.findById(req.getId()).ifPresent(t ->
          t.setRemarks(req.getRemarks() == null ? "" : req.getRemarks().trim())
      );
    }
  }

  // ===== 내부 유틸 =====

  private BankTransactionRowResponse toRow(BankTransaction t) {
    return BankTransactionRowResponse.builder()
        .id(t.getId())
        .bankName(t.getBankName())
        .accountNo(t.getAccountNo())
        .txDate(t.getTxDate())
        .depositAmount(t.getDepositAmount())
        .withdrawalAmount(t.getWithdrawalAmount())
        .balance(t.getBalance())
        .summary(t.getSummary())
        .remarks(t.getRemarks())
        .build();
  }

  private String safe(String s) { return s == null ? "" : s.trim(); }
  private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

  private LocalDate parseDate(String s) {
    String v = safe(s).replace(".", "-").replace("/", "-").replace(" ", "");
    if (v.matches("^\\d{8}$")) {
      v = v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8);
    }
    return LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
  }

  private long parseMoney(String s) {
    String v = safe(s);
    if (v.isBlank() || v.equals("-")) return 0L;
    v = v.replace(",", "").replace("원", "").replace("₩", "").replace(" ", "");
    if (v.isBlank()) return 0L;
    if (v.startsWith("(") && v.endsWith(")")) v = "-" + v.substring(1, v.length() - 1);
    try { return Long.parseLong(v); } catch (Exception e) { return 0L; }
  }

  private String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException("해시 생성 실패: " + e.getMessage());
    }
  }
}
