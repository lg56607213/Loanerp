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
  private static final List<String> EXCEL_SAMPLE   = List.of("20260101", "1000000", "", "5000000", "이자수납");

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
    return uploadExcel(bankName, accountNo, file, false);
  }

  /**
   * @param dryRun true 면 검증만 하고 저장하지 않는다.
   *               무엇이 빠지는지 먼저 보여주고 사용자가 결정하게 하기 위한 것이다.
   */
  public UploadResultResponse uploadExcel(String bankName, String accountNo, MultipartFile file, boolean dryRun) {
    if (file == null || file.isEmpty()) throw new RuntimeException("파일이 비어있습니다.");
    if (isBlank(bankName)) throw new RuntimeException("은행명을 입력하세요.");
    if (isBlank(accountNo)) throw new RuntimeException("계좌번호를 입력하세요.");

    String bn = bankName.trim();
    String an = accountNo.trim();
    String batchId = UUID.randomUUID().toString().replace("-", "");

    ExcelReader.Sheet sheet;
    try {
      sheet = ExcelReader.readSheet(file.getInputStream());
    } catch (Exception e) {
      throw new RuntimeException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
    }

    // 필수 열이 아예 없으면 행을 하나씩 따질 것도 없이 바로 알려준다.
    // (예전에는 헤더가 틀려도 전 행이 조용히 걸러져 "0건"만 나왔다)
    List<String> missing = new ArrayList<>();
    for (String required : List.of("일자")) {
      if (sheet.getHeaders().stream().noneMatch(h -> h.equals(required))) missing.add(required);
    }
    if (!missing.isEmpty()) {
      throw new RuntimeException(
          "엑셀 첫 행에 필수 열이 없습니다: " + String.join(", ", missing)
          + " (현재 첫 행: " + String.join(" | ", sheet.getHeaders()) + ")"
          + " — '양식 다운로드'로 받은 서식을 사용하세요.");
    }

    List<ExcelReader.IndexedRow> rows = sheet.getRows();
    int inserted = 0, skipped = 0;
    List<UploadResultResponse.RowError> errors = new ArrayList<>();
    List<UploadResultResponse.DuplicateRow> duplicates = new ArrayList<>();
    // 같은 파일 안에서 겹치는 행도 잡아낸다(DB 조회만으로는 저장 전이라 걸리지 않는다).
    Set<String> seenInFile = new HashSet<>();

    for (ExcelReader.IndexedRow indexed : rows) {
      Map<String, String> row = indexed.getValues();
      int rowNo = indexed.getRowNumber();

      String dateStr = safe(row.get("일자"));
      if (dateStr.isBlank()) {
        errors.add(rowError(rowNo, "일자가 비어 있습니다.", ""));
        continue;
      }

      LocalDate txDate;
      try {
        txDate = parseDate(dateStr);
      } catch (Exception e) {
        errors.add(rowError(rowNo,
            "일자를 날짜로 읽을 수 없습니다. 엑셀에서 해당 셀 서식을 '2026-01-31' 형태로 바꿔주세요.",
            dateStr));
        continue;
      }

      long deposit    = parseMoney(row.get("입금액"));
      long withdrawal = parseMoney(row.get("출금액"));
      long balance    = parseMoney(row.get("잔액"));
      String summary  = safe(row.get("적요"));

      if (deposit == 0 && withdrawal == 0) {
        errors.add(rowError(rowNo, "입금액과 출금액이 모두 비어 있습니다.",
            "입금=" + safe(row.get("입금액")) + " / 출금=" + safe(row.get("출금액"))));
        continue;
      }

      // 중복 판정에는 잔액까지 넣는다. 빼면 같은 날 같은 금액을 같은 상대와
      // 두 번 주고받은 서로 다른 거래가 하나로 묶여 사라진다.
      // 저장용 해시도 같은 기준으로 만든다(row_hash 에 unique 제약이 있어,
      // 잔액을 빼면 그런 두 거래가 제약에 걸려 아예 들어가지 못한다).
      String rowHash = sha256(String.join("|", bn, an, txDate.toString(),
          String.valueOf(deposit), String.valueOf(withdrawal),
          String.valueOf(balance), summary));

      String dupReason = null;
      if (!seenInFile.add(rowHash)) {
        dupReason = "이 파일 안에서 중복";
      } else if (repo.existsSameTransaction(bn, an, txDate, deposit, withdrawal, balance, summary)) {
        dupReason = "이미 등록된 내역";
      }

      if (dupReason != null) {
        skipped++;
        duplicates.add(UploadResultResponse.DuplicateRow.builder()
            .rowNumber(rowNo).txDate(txDate.toString())
            .deposit(deposit).withdrawal(withdrawal).balance(balance)
            .summary(summary).reason(dupReason).build());
        continue;
      }

      if (dryRun) { inserted++; continue; }   // 검증만 — 저장하지 않는다

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
        .failedRows(errors.size())
        .errors(errors)
        .duplicates(duplicates)
        .dryRun(dryRun)
        .build();
  }

  private UploadResultResponse.RowError rowError(int rowNumber, String reason, String value) {
    return UploadResultResponse.RowError.builder()
        .rowNumber(rowNumber).reason(reason).value(value).build();
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

  /**
   * 날짜 파싱은 엑셀 업로드 공통 규칙을 따른다(ExcelRowParsers.parseDate).
   *
   * 은행 파일은 표기가 제각각이고, 양식에 적는 8자리 숫자(20260630)도 받아야 한다.
   * 같은 규칙을 두 벌 두면 한쪽만 고쳐져 어느 파일은 읽히고 어느 파일은 탈락한다.
   */
  private LocalDate parseDate(String s) {
    return com.jdend.erp.common.excel.ExcelRowParsers.parseDate(s);
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
