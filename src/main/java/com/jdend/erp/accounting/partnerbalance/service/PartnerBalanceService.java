package com.jdend.erp.accounting.partnerbalance.service;

import com.jdend.erp.accounting.partnerbalance.dto.*;
import com.jdend.erp.accounting.partnerbalance.entity.PartnerBalance;
import com.jdend.erp.accounting.partnerbalance.repository.PartnerBalanceRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 거래처 미수금·미지급금 등록·조회·회수(지급) 기록 */
@Service
@RequiredArgsConstructor
@Transactional
public class PartnerBalanceService {

  private final PartnerBalanceRepository repo;

  private static final List<String> HEADERS = List.of(
      "구분", "거래처명", "사업자등록번호", "발생일", "약정일",
      "발생금액", "회수지급액", "근거", "내용", "비고");

  private static final List<List<String>> SAMPLE_ROWS = List.of(
      List.of("미수금", "주식회사 가나", "123-45-67890", "20260731", "20260831",
              "84000000", "0", "20260731-10260810-3924968", "7월 물류비", ""),
      List.of("미지급금", "다라상사", "222-33-44444", "20260626", "",
              "44000000", "4400000", "", "개발용역", "일부 지급"));

  // ── 조회 ────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<PartnerBalanceRowResponse> search(String balanceType, String kw,
                                                LocalDate startDate, LocalDate endDate,
                                                boolean openOnly) {
    LocalDate today = LocalDate.now();
    return repo.search(safe(balanceType), safe(kw), startDate, endDate).stream()
        .filter(p -> !openOnly || p.balance() != 0)
        .map(p -> toRow(p, today))
        .toList();
  }

  /** 거래처별로 묶은 잔액 — 누구에게 얼마가 남았는지 한눈에 본다. */
  @Transactional(readOnly = true)
  public PartnerBalanceSummaryResponse summary(String balanceType, LocalDate startDate,
                                               LocalDate endDate, boolean openOnly) {
    LocalDate today = LocalDate.now();
    List<PartnerBalance> all = repo.search(safe(balanceType), "", startDate, endDate).stream()
        .filter(p -> !openOnly || p.balance() != 0)
        .toList();

    Map<String, List<PartnerBalance>> byKey = new LinkedHashMap<>();
    for (PartnerBalance p : all) {
      byKey.computeIfAbsent(p.getBalanceType() + '\u0000' + p.getPartnerName(),
          k -> new ArrayList<>()).add(p);
    }

    List<PartnerBalanceSummaryResponse.Row> rows = new ArrayList<>();
    for (Map.Entry<String, List<PartnerBalance>> e : byKey.entrySet()) {
      List<PartnerBalance> list = e.getValue();
      Integer oldest = null;
      for (PartnerBalance p : list) {
        if (p.balance() == 0) continue;
        int days = (int) ChronoUnit.DAYS.between(p.getOccurredDate(), today);
        if (oldest == null || days > oldest) oldest = days;
      }
      rows.add(PartnerBalanceSummaryResponse.Row.builder()
          .balanceType(list.get(0).getBalanceType())
          .partnerName(list.get(0).getPartnerName())
          .count(list.size())
          .amount(list.stream().mapToLong(p -> nz(p.getAmount())).sum())
          .settledAmount(list.stream().mapToLong(p -> nz(p.getSettledAmount())).sum())
          .balance(list.stream().mapToLong(PartnerBalance::balance).sum())
          .oldestAgingDays(oldest)
          .build());
    }
    rows.sort(Comparator
        .comparing(PartnerBalanceSummaryResponse.Row::getBalanceType)
        .thenComparing(Comparator.comparingLong(
            PartnerBalanceSummaryResponse.Row::getBalance).reversed()));

    return PartnerBalanceSummaryResponse.builder()
        .receivableTotal(rows.stream()
            .filter(r -> PartnerBalance.RECEIVABLE.equals(r.getBalanceType()))
            .mapToLong(PartnerBalanceSummaryResponse.Row::getBalance).sum())
        .payableTotal(rows.stream()
            .filter(r -> PartnerBalance.PAYABLE.equals(r.getBalanceType()))
            .mapToLong(PartnerBalanceSummaryResponse.Row::getBalance).sum())
        .rows(rows)
        .build();
  }

  // ── 단건 ────────────────────────────────────────────────

  public PartnerBalanceRowResponse create(PartnerBalanceRequest req) {
    PartnerBalance p = new PartnerBalance();
    apply(p, req);
    p.setRowHash(hashOf(p));
    return toRow(repo.save(p), LocalDate.now());
  }

  public PartnerBalanceRowResponse update(Long id, PartnerBalanceRequest req) {
    PartnerBalance p = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("찾을 수 없습니다. id=" + id));
    apply(p, req);
    p.setRowHash(hashOf(p));
    return toRow(repo.save(p), LocalDate.now());
  }

  /**
   * 회수(지급)를 쌓는다.
   *
   * @param addAmount 이번에 받은(준) 금액. 음수를 주면 되돌린다.
   */
  public PartnerBalanceRowResponse settle(Long id, long addAmount) {
    PartnerBalance p = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("찾을 수 없습니다. id=" + id));
    long next = nz(p.getSettledAmount()) + addAmount;
    if (next < 0) {
      throw new IllegalArgumentException("회수(지급) 누계가 0 보다 작아집니다.");
    }
    // 발생금액이 음수인 건(수정분)은 부호가 반대라 한도를 절대값으로 본다.
    if (Math.abs(next) > Math.abs(nz(p.getAmount()))) {
      throw new IllegalArgumentException(
          "발생금액(" + nz(p.getAmount()) + ")보다 많이 회수(지급)할 수 없습니다. 입력 누계: " + next);
    }
    p.setSettledAmount(next);
    return toRow(repo.save(p), LocalDate.now());
  }

  public void delete(List<Long> ids) {
    if (ids == null || ids.isEmpty()) return;
    repo.deleteAllById(ids);
  }

  // ── 엑셀 ────────────────────────────────────────────────

  public byte[] template() {
    return ExcelTemplateWriter.writeMultiRow(HEADERS, SAMPLE_ROWS);
  }

  public PartnerBalanceUploadResultResponse upload(MultipartFile file, boolean dryRun) {
    if (file == null || file.isEmpty()) throw new RuntimeException("파일이 비어있습니다.");

    ExcelReader.Sheet sheet;
    try {
      sheet = ExcelReader.readSheet(file.getInputStream());
    } catch (Exception e) {
      throw new RuntimeException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
    }

    List<String> missing = new ArrayList<>();
    for (String required : List.of("구분", "거래처명", "발생일")) {
      if (sheet.getHeaders().stream().noneMatch(h -> h.equals(required))) missing.add(required);
    }
    if (!missing.isEmpty()) {
      throw new RuntimeException(
          "엑셀 첫 행에 필수 열이 없습니다: " + String.join(", ", missing)
          + " (현재 첫 행: " + String.join(" | ", sheet.getHeaders()) + ")"
          + " — '양식 다운로드' 로 받은 서식을 사용하세요.");
    }

    int inserted = 0, skipped = 0;
    List<PartnerBalanceUploadResultResponse.RowError> errors = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<PartnerBalance> batch = new ArrayList<>();

    for (ExcelReader.IndexedRow indexed : sheet.getRows()) {
      Map<String, String> row = indexed.getValues();
      int rowNo = indexed.getRowNumber();

      String type = normalizeType(row.get("구분"));
      if (type == null) {
        errors.add(err(rowNo, "구분은 '미수금' 또는 '미지급금' 이어야 합니다.", safe(row.get("구분"))));
        continue;
      }
      String partner = safe(row.get("거래처명"));
      if (partner.isBlank()) {
        errors.add(err(rowNo, "거래처명이 비어 있습니다.", ""));
        continue;
      }
      LocalDate occurred;
      try {
        occurred = parseDate(row.get("발생일"));
      } catch (Exception e) {
        errors.add(err(rowNo, "발생일을 날짜로 읽을 수 없습니다. 8자리 숫자(20260731)로 적어주세요.",
            safe(row.get("발생일"))));
        continue;
      }
      LocalDate due = null;
      if (!safe(row.get("약정일")).isBlank()) {
        try {
          due = parseDate(row.get("약정일"));
        } catch (Exception ignored) {
          // 약정일은 없어도 된다 — 읽히지 않으면 비워 둔다
        }
      }
      long amount = parseMoney(row.get("발생금액"));
      if (amount == 0) {
        errors.add(err(rowNo, "발생금액이 없습니다.", safe(row.get("발생금액"))));
        continue;
      }
      long settled = parseMoney(row.get("회수지급액"));

      PartnerBalance p = PartnerBalance.builder()
          .balanceType(type).partnerName(partner)
          .partnerBizNo(blankToNull(row.get("사업자등록번호")))
          .occurredDate(occurred).dueDate(due)
          .amount(amount).settledAmount(settled)
          .source(blankToNull(row.get("근거")))
          .item(blankToNull(row.get("내용")))
          .remarks(blankToNull(row.get("비고")))
          .build();
      p.setRowHash(hashOf(p));

      if (seen.contains(p.getRowHash()) || repo.existsByRowHash(p.getRowHash())) {
        skipped++;
        continue;
      }
      seen.add(p.getRowHash());
      batch.add(p);
      inserted++;
    }

    if (!dryRun && !batch.isEmpty()) repo.saveAll(batch);

    return PartnerBalanceUploadResultResponse.builder()
        .parsedRows(sheet.getRows().size())
        .insertedRows(inserted)
        .skippedDuplicates(skipped)
        .failedRows(errors.size())
        .dryRun(dryRun)
        .errors(errors)
        .build();
  }

  // ── 내부 ────────────────────────────────────────────────

  private void apply(PartnerBalance p, PartnerBalanceRequest req) {
    String type = normalizeType(req.getBalanceType());
    if (type == null) throw new IllegalArgumentException("구분은 '미수금' 또는 '미지급금' 이어야 합니다.");
    if (safe(req.getPartnerName()).isBlank()) throw new IllegalArgumentException("거래처명은 필수입니다.");
    if (req.getOccurredDate() == null) throw new IllegalArgumentException("발생일은 필수입니다.");
    if (nz(req.getAmount()) == 0) throw new IllegalArgumentException("발생금액은 필수입니다.");

    p.setBalanceType(type);
    p.setPartnerName(req.getPartnerName().trim());
    p.setPartnerBizNo(blankToNull(req.getPartnerBizNo()));
    p.setOccurredDate(req.getOccurredDate());
    p.setDueDate(req.getDueDate());
    p.setAmount(req.getAmount());
    p.setSettledAmount(nz(req.getSettledAmount()));
    p.setSource(blankToNull(req.getSource()));
    p.setItem(blankToNull(req.getItem()));
    p.setRemarks(blankToNull(req.getRemarks()));
  }

  private static String normalizeType(String v) {
    String s = safe(v);
    if (s.startsWith("미수")) return PartnerBalance.RECEIVABLE;
    if (s.startsWith("미지급")) return PartnerBalance.PAYABLE;
    return null;
  }

  private static PartnerBalanceRowResponse toRow(PartnerBalance p, LocalDate today) {
    Integer aging = p.balance() == 0 ? null
        : (int) ChronoUnit.DAYS.between(p.getOccurredDate(), today);
    return PartnerBalanceRowResponse.builder()
        .id(p.getId()).balanceType(p.getBalanceType())
        .partnerName(p.getPartnerName()).partnerBizNo(p.getPartnerBizNo())
        .occurredDate(p.getOccurredDate()).dueDate(p.getDueDate())
        .amount(p.getAmount()).settledAmount(p.getSettledAmount())
        .balance(p.balance()).status(p.status()).agingDays(aging)
        .source(p.getSource()).item(p.getItem()).remarks(p.getRemarks())
        .build();
  }

  private static String hashOf(PartnerBalance p) {
    String seed = String.join("|", String.valueOf(p.getBalanceType()),
        String.valueOf(p.getPartnerName()), String.valueOf(p.getOccurredDate()),
        String.valueOf(p.getAmount()), String.valueOf(p.getSource()),
        String.valueOf(p.getItem()));
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      StringBuilder sb = new StringBuilder();
      for (byte b : md.digest(seed.getBytes(StandardCharsets.UTF_8))) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      return seed;
    }
  }

  private static PartnerBalanceUploadResultResponse.RowError err(int rowNo, String reason, String value) {
    return PartnerBalanceUploadResultResponse.RowError.builder()
        .rowNumber(rowNo).reason(reason).value(value).build();
  }

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
