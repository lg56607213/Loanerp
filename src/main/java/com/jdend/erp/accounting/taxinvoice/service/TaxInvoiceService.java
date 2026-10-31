package com.jdend.erp.accounting.taxinvoice.service;

import com.jdend.erp.accounting.taxinvoice.dto.TaxInvoiceRequest;
import com.jdend.erp.accounting.taxinvoice.dto.TaxInvoiceRowResponse;
import com.jdend.erp.accounting.taxinvoice.dto.TaxInvoiceUploadResultResponse;
import com.jdend.erp.accounting.taxinvoice.entity.TaxInvoice;
import com.jdend.erp.accounting.taxinvoice.repository.TaxInvoiceRepository;
import com.jdend.erp.common.excel.ExcelReader;
import com.jdend.erp.common.excel.ExcelTemplateWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 세금계산서 내역 등록·조회·엑셀 업로드 */
@Service
@RequiredArgsConstructor
@Transactional
public class TaxInvoiceService {

  private final TaxInvoiceRepository repo;

  private static final List<String> HEADERS = List.of(
      "구분", "작성일자", "승인번호", "거래처명", "사업자등록번호",
      "공급가액", "세액", "합계", "품목", "비고");

  private static final List<List<String>> SAMPLE_ROWS = List.of(
      List.of("매출", "20260131", "20260131-410001-1a2b", "주식회사 가나", "123-45-67890",
              "1000000", "100000", "1100000", "용역대금", ""),
      List.of("매입", "20260205", "", "다라상사", "222-33-44444",
              "500000", "50000", "550000", "소모품", "카드결제"));

  // ── 조회 ────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<TaxInvoiceRowResponse> search(String invoiceType, String kw,
                                            LocalDate startDate, LocalDate endDate) {
    return repo.search(safe(invoiceType), safe(kw), startDate, endDate).stream()
        .map(TaxInvoiceService::toRow)
        .toList();
  }

  // ── 단건 ────────────────────────────────────────────────

  public TaxInvoiceRowResponse create(TaxInvoiceRequest req) {
    TaxInvoice t = new TaxInvoice();
    apply(t, req);
    t.setRowHash(hashOf(t));
    return toRow(repo.save(t));
  }

  public TaxInvoiceRowResponse update(Long id, TaxInvoiceRequest req) {
    TaxInvoice t = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("세금계산서를 찾을 수 없습니다. id=" + id));
    apply(t, req);
    t.setRowHash(hashOf(t));
    return toRow(repo.save(t));
  }

  public void delete(List<Long> ids) {
    if (ids == null || ids.isEmpty()) return;
    repo.deleteAllById(ids);
  }

  // ── 엑셀 ────────────────────────────────────────────────

  public byte[] template() {
    return ExcelTemplateWriter.writeMultiRow(HEADERS, SAMPLE_ROWS);
  }

  /**
   * @param dryRun true 면 검증만 하고 저장하지 않는다 — 무엇이 빠지는지 먼저 보여준다.
   */
  public TaxInvoiceUploadResultResponse upload(MultipartFile file, boolean dryRun) {
    if (file == null || file.isEmpty()) throw new RuntimeException("파일이 비어있습니다.");

    ExcelReader.Sheet sheet;
    try {
      sheet = ExcelReader.readSheet(file.getInputStream());
    } catch (Exception e) {
      throw new RuntimeException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
    }

    // 머리글이 틀리면 행마다 따질 것 없이 바로 알려준다.
    // 그러지 않으면 전 행이 조용히 걸러져 '0건' 만 나온다.
    List<String> missing = new ArrayList<>();
    for (String required : List.of("구분", "작성일자", "거래처명")) {
      if (sheet.getHeaders().stream().noneMatch(h -> h.equals(required))) missing.add(required);
    }
    if (!missing.isEmpty()) {
      throw new RuntimeException(
          "엑셀 첫 행에 필수 열이 없습니다: " + String.join(", ", missing)
          + " (현재 첫 행: " + String.join(" | ", sheet.getHeaders()) + ")"
          + " — '양식 다운로드' 로 받은 서식을 사용하세요.");
    }

    String batchId = UUID.randomUUID().toString().replace("-", "");
    int inserted = 0, skipped = 0;
    List<TaxInvoiceUploadResultResponse.RowError> errors = new ArrayList<>();
    List<String> duplicates = new ArrayList<>();
    Set<String> seenInFile = new HashSet<>();
    List<TaxInvoice> batch = new ArrayList<>();

    for (ExcelReader.IndexedRow indexed : sheet.getRows()) {
      Map<String, String> row = indexed.getValues();
      int rowNo = indexed.getRowNumber();

      String type = normalizeType(row.get("구분"));
      if (type == null) {
        errors.add(err(rowNo, "구분은 '매출' 또는 '매입' 이어야 합니다.", safe(row.get("구분"))));
        continue;
      }

      String dateStr = safe(row.get("작성일자"));
      LocalDate issueDate;
      try {
        issueDate = parseDate(dateStr);
      } catch (Exception e) {
        errors.add(err(rowNo,
            "작성일자를 날짜로 읽을 수 없습니다. 8자리 숫자(20260131)로 적어주세요.", dateStr));
        continue;
      }

      String partner = safe(row.get("거래처명"));
      if (partner.isBlank()) {
        errors.add(err(rowNo, "거래처명이 비어 있습니다.", ""));
        continue;
      }

      long supply = parseMoney(row.get("공급가액"));
      long tax = parseMoney(row.get("세액"));
      long total = parseMoney(row.get("합계"));
      if (total == 0) total = supply + tax;
      if (total == 0) {
        errors.add(err(rowNo, "금액이 없습니다. 공급가액·세액 또는 합계를 적어주세요.", ""));
        continue;
      }
      // 금액이 음수인 것은 수정세금계산서(취소·감액)다. 홈택스 목록에 그렇게 나오고
      // 그 건을 빼면 매출·매입 합계가 신고분과 어긋나므로 그대로 받는다.
      // 합계만 적고 공급가액·세액을 비운 경우 10% 부가세로 나눠 채운다.
      if (supply == 0 && tax == 0) {
        supply = Math.round(total / 1.1);
        tax = total - supply;
      }

      TaxInvoice t = TaxInvoice.builder()
          .invoiceType(type)
          .issueDate(issueDate)
          .approvalNo(blankToNull(row.get("승인번호")))
          .partnerName(partner)
          .partnerBizNo(blankToNull(row.get("사업자등록번호")))
          .supplyAmount(supply)
          .taxAmount(tax)
          .totalAmount(total)
          .item(blankToNull(row.get("품목")))
          .remarks(blankToNull(row.get("비고")))
          .batchId(batchId)
          .build();
      t.setRowHash(hashOf(t));

      // 승인번호가 있으면 그것만 본다.
      //
      // 전에는 승인번호가 있어도 행 지문(일자·거래처·금액·품목)까지 함께 봤다.
      // 같은 날 같은 거래처에 같은 금액으로 두 건을 발행하면 승인번호가 달라도
      // 지문이 같아서 뒤의 것이 중복으로 걸러졌다 — 제이디엔드 26년 매출에서
      // 실제로 그런 건이 있어 65,000원이 빠졌다. 지문은 승인번호가 없을 때만 쓴다.
      String approval = t.getApprovalNo();
      boolean dup = (approval != null)
          ? (seenInFile.contains("A:" + approval) || repo.existsByApprovalNo(approval))
          : (seenInFile.contains("H:" + t.getRowHash()) || repo.existsByRowHash(t.getRowHash()));
      if (dup) {
        skipped++;
        duplicates.add(rowNo + "행 " + type + " " + issueDate + " " + partner + " " + total + "원");
        continue;
      }
      if (approval != null) seenInFile.add("A:" + approval);
      seenInFile.add("H:" + t.getRowHash());

      batch.add(t);
      inserted++;
    }

    if (!dryRun && !batch.isEmpty()) repo.saveAll(batch);

    return TaxInvoiceUploadResultResponse.builder()
        .batchId(batchId)
        .parsedRows(sheet.getRows().size())
        .insertedRows(inserted)
        .skippedDuplicates(skipped)
        .failedRows(errors.size())
        .dryRun(dryRun)
        .errors(errors)
        .duplicates(duplicates)
        .build();
  }

  // ── 내부 ────────────────────────────────────────────────

  private void apply(TaxInvoice t, TaxInvoiceRequest req) {
    String type = normalizeType(req.getInvoiceType());
    if (type == null) throw new IllegalArgumentException("구분은 '매출' 또는 '매입' 이어야 합니다.");
    if (req.getIssueDate() == null) throw new IllegalArgumentException("작성일자는 필수입니다.");
    if (safe(req.getPartnerName()).isBlank()) throw new IllegalArgumentException("거래처명은 필수입니다.");

    long supply = nz(req.getSupplyAmount());
    long tax = nz(req.getTaxAmount());
    long total = nz(req.getTotalAmount());
    if (total == 0) total = supply + tax;
    if (total == 0) throw new IllegalArgumentException("금액은 필수입니다.");
    // 음수는 수정세금계산서(취소·감액)다 — 그대로 받는다.
    if (supply == 0 && tax == 0) {
      supply = Math.round(total / 1.1);
      tax = total - supply;
    }

    t.setInvoiceType(type);
    t.setIssueDate(req.getIssueDate());
    t.setApprovalNo(blankToNull(req.getApprovalNo()));
    t.setPartnerName(req.getPartnerName().trim());
    t.setPartnerBizNo(blankToNull(req.getPartnerBizNo()));
    t.setSupplyAmount(supply);
    t.setTaxAmount(tax);
    t.setTotalAmount(total);
    t.setItem(blankToNull(req.getItem()));
    t.setRemarks(blankToNull(req.getRemarks()));
  }

  private static String normalizeType(String v) {
    String s = safe(v);
    if (s.startsWith("매출") || s.equalsIgnoreCase("sales")) return TaxInvoice.SALES;
    if (s.startsWith("매입") || s.equalsIgnoreCase("purchase")) return TaxInvoice.PURCHASE;
    return null;
  }

  private static String hashOf(TaxInvoice t) {
    String seed = String.join("|",
        String.valueOf(t.getInvoiceType()), String.valueOf(t.getIssueDate()),
        String.valueOf(t.getPartnerName()), String.valueOf(t.getTotalAmount()),
        String.valueOf(t.getItem()));
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(seed.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : d) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      return seed;
    }
  }

  private static TaxInvoiceRowResponse toRow(TaxInvoice t) {
    return TaxInvoiceRowResponse.builder()
        .id(t.getId()).invoiceType(t.getInvoiceType()).issueDate(t.getIssueDate())
        .approvalNo(t.getApprovalNo()).partnerName(t.getPartnerName())
        .partnerBizNo(t.getPartnerBizNo()).supplyAmount(t.getSupplyAmount())
        .taxAmount(t.getTaxAmount()).totalAmount(t.getTotalAmount())
        .item(t.getItem()).remarks(t.getRemarks())
        .build();
  }

  private static TaxInvoiceUploadResultResponse.RowError err(int rowNo, String reason, String value) {
    return TaxInvoiceUploadResultResponse.RowError.builder()
        .rowNumber(rowNo).reason(reason).value(value).build();
  }

  /** 8자리 숫자(20260131) 와 2026-01-31 둘 다 받는다. */
  static LocalDate parseDate(String raw) {
    String s = safe(raw).replace(".", "-").replace("/", "-");
    if (s.isBlank()) throw new IllegalArgumentException("빈 값");
    if (s.matches("\\d{8}")) return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
    return LocalDate.parse(s);
  }

  static long parseMoney(String raw) {
    String s = safe(raw).replace(",", "").replace("원", "").trim();
    if (s.isBlank()) return 0L;
    if (s.startsWith("(") && s.endsWith(")")) s = "-" + s.substring(1, s.length() - 1);
    try {
      return Math.round(Double.parseDouble(s));
    } catch (Exception e) {
      return 0L;
    }
  }

  private static String safe(String v) { return v == null ? "" : v.trim(); }
  private static String blankToNull(String v) { String s = safe(v); return s.isBlank() ? null : s; }
  private static long nz(Long v) { return v == null ? 0L : v; }
}
