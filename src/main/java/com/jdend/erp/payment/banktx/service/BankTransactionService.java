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
   * 은행에서 받은 파일은 날짜 표기가 제각각이라 넓게 받는다.
   * POI 는 셀 서식대로 문자열을 만들어 주므로, 서식이 m/d/yyyy 인 셀은
   * "8/7/2023" 처럼 넘어온다. 예전에는 yyyy-MM-dd 만 받아 이런 행이 전부 탈락했다.
   */
  private LocalDate parseDate(String s) {
    String raw = safe(s);
    if (raw.isBlank()) throw new IllegalArgumentException("빈 값");

    // 엑셀 날짜 일련번호가 서식 없이 그대로 넘어온 경우.
    // 엑셀은 1900-02-29 를 존재한 날처럼 세므로 기준일이 1899-12-30 이다.
    if (raw.matches("^\\d{5}(\\.0+)?$")) {
      long serial = (long) Double.parseDouble(raw);
      return LocalDate.of(1899, 12, 30).plusDays(serial);
    }

    // 엑셀이 20260801 을 천단위 서식으로 "20,260,801" 로 내려주는 경우가 있어 쉼표를 먼저 턴다.
    String v = raw.replace(",", "").replace(".", "-").replace("/", "-").replace(" ", "");
    v = v.replace("년", "-").replace("월", "-").replace("일", "");
    v = v.replaceAll("-+$", "");

    if (v.matches("^\\d{8}$")) {
      v = v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8);
    }

    String[] p = v.split("-");
    if (p.length == 3) {
      // 표기가 여러 가지라 후보를 만들어 보고 실제로 존재하는 날짜를 고른다.
      // 엑셀 기본 날짜 서식(numFmtId 14)은 m/d/yy 라 "12/29/23" 처럼 두 자리 연도로 온다.
      Integer a = toInt(p[0]), b = toInt(p[1]), c = toInt(p[2]);
      if (a != null && b != null && c != null) {
        if (p[0].length() == 4) {
          LocalDate d = tryDate(a, b, c);            // yyyy-M-d
          if (d != null) return d;
        }
        if (p[2].length() == 4) {
          LocalDate d = tryDate(c, a, b);            // M-d-yyyy
          if (d != null) return d;
        }
        if (p[2].length() == 2) {
          LocalDate d = tryDate(2000 + c, a, b);     // M-d-yy (엑셀 기본)
          if (d != null) return d;
        }
        if (p[0].length() == 2) {
          LocalDate d = tryDate(2000 + a, b, c);     // yy-M-d
          if (d != null) return d;
        }
      }
    }
    return LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
  }

  private Integer toInt(String s) {
    try { return Integer.parseInt(s); } catch (Exception e) { return null; }
  }

  /** 존재하지 않는 날짜(13월 등)면 null 을 돌려 다음 후보로 넘어가게 한다. */
  private LocalDate tryDate(int year, int month, int day) {
    try { return LocalDate.of(year, month, day); } catch (Exception e) { return null; }
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
