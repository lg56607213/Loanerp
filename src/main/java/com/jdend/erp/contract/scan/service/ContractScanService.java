package com.jdend.erp.contract.scan.service;

import com.jdend.erp.auth.exception.NotFoundException;
import com.jdend.erp.auth.service.AuthService;
import com.jdend.erp.config.TenantContext;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.contract.scan.dto.ContractScanResponse;
import com.jdend.erp.contract.scan.dto.ScanContractRowResponse;
import com.jdend.erp.contract.scan.entity.ContractScan;
import com.jdend.erp.contract.scan.repository.ContractScanRepository;
import com.jdend.erp.contract.scan.support.ScanDocCategory;
import com.jdend.erp.contract.scan.support.ScanFileRule;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 채권 스캔 서류 보관.
 *
 * <p><b>저장 위치</b> — uploads/scans/{회사DB}/{계약번호}/ 아래에 둔다. 회사별로 나누는
 * 이유는 한 서버가 여러 회사를 받기 때문이다. 파일 이름만으로는 어느 회사 것인지
 * 알 수 없으니, DB 가 섞이지 않는 것과 같은 수준으로 디스크도 나눠 둔다.
 *
 * <p><b>파일 이름</b> — 올린 이름을 그대로 쓰지 않고 UUID 로 바꿔 저장한다. 올린 이름에는
 * 한글·공백·괄호가 섞이고 같은 이름이 또 올라오며, 상위 폴더를 가리키는 조각이 들어올
 * 수도 있다. 보여줄 이름은 DB 에 남겨 두고 내려줄 때 다시 붙인다.
 *
 * <p><b>지우기</b> — DB 행과 파일을 같이 지운다. 파일이 이미 없어도 행은 지운다.
 * 반대로 행이 남아 있는데 파일이 없으면 화면에서 열리지 않는 줄이 되어 더 혼란스럽다.
 */
@Service
@RequiredArgsConstructor
public class ContractScanService {

  private final ContractScanRepository scanRepo;
  private final ContractRepository contractRepo;

  @Value("${app.scan-upload-dir:uploads/scans}")
  private String scanUploadDir;

  // ── 조회 ──────────────────────────────────────────────────

  /** 스캔관리 왼쪽 채권 목록. withScanOnly 면 서류가 하나라도 있는 건만. */
  @Transactional(readOnly = true)
  public List<ScanContractRowResponse> contracts(String keyword, boolean withScanOnly) {
    List<Contract> found = contractRepo.searchTop200(keyword == null ? "" : keyword.trim());
    if (found.isEmpty()) return List.of();

    List<String> numbers = found.stream().map(Contract::getContractNumber).toList();

    // [계약번호][구분] → 건수, [계약번호] → 마지막 등록시각
    Map<String, Map<String, Long>> counts = new HashMap<>();
    Map<String, LocalDateTime> lastUploaded = new HashMap<>();

    for (Object[] row : scanRepo.countByContracts(numbers)) {
      String cn = (String) row[0];
      String category = (String) row[1];
      long count = ((Number) row[2]).longValue();
      LocalDateTime last = (LocalDateTime) row[3];

      counts.computeIfAbsent(cn, k -> new HashMap<>()).merge(category, count, Long::sum);
      if (last != null) {
        lastUploaded.merge(cn, last, (a, b) -> b.isAfter(a) ? b : a);
      }
    }

    List<ScanContractRowResponse> out = new ArrayList<>();
    for (Contract c : found) {
      Map<String, Long> byCategory = counts.getOrDefault(c.getContractNumber(), Map.of());
      long total = byCategory.values().stream().mapToLong(Long::longValue).sum();
      if (withScanOnly && total == 0) continue;

      out.add(ScanContractRowResponse.builder()
          .contractNumber(c.getContractNumber())
          .customerName(c.getCustomer() != null ? c.getCustomer().getCustomerName() : null)
          .loanType(c.getLoanType())
          .loanAmount(c.getLoanAmount())
          .startDate(c.getStartDate())
          .endDate(c.getEndDate())
          .totalCount(total)
          .contractDocCount(byCategory.getOrDefault(ScanDocCategory.CONTRACT, 0L))
          .collectionDocCount(byCategory.getOrDefault(ScanDocCategory.COLLECTION, 0L))
          .legalDocCount(byCategory.getOrDefault(ScanDocCategory.LEGAL, 0L))
          .lastUploadedAt(lastUploaded.get(c.getContractNumber()))
          .build());
    }
    return out;
  }

  /** 한 채권의 스캔 목록. category 가 비면 전체. */
  @Transactional(readOnly = true)
  public List<ContractScanResponse> list(String contractNumber, String category) {
    String cn = requireContract(contractNumber);
    String filter = ScanDocCategory.filterOrNull(category);
    return scanRepo.findForContract(cn, filter == null ? "" : filter)
        .stream().map(ContractScanResponse::of).toList();
  }

  // ── 등록 ──────────────────────────────────────────────────

  /**
   * 스캔 올리기. 여러 장을 한 번에 받는다 — 서류 한 건이 여러 장으로 스캔되는 일이 많다.
   *
   * <p>한 장이라도 조건에 맞지 않으면 아무것도 저장하지 않고 멈춘다. 절반만 올라가면
   * 어느 장이 빠졌는지 화면에서 알기 어렵고, 같은 서류를 다시 올려 중복이 생긴다.
   */
  @Transactional
  public List<ContractScanResponse> upload(String contractNumber,
                                           String docCategory,
                                           String docTitle,
                                           LocalDate docDate,
                                           String memo,
                                           List<MultipartFile> files,
                                           HttpSession session) {
    String cn = requireContract(contractNumber);
    String category = ScanDocCategory.require(docCategory);

    if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
      throw new IllegalArgumentException("올릴 파일을 선택하세요.");
    }

    List<MultipartFile> targets = files.stream().filter(f -> !f.isEmpty()).toList();

    // 먼저 전부 검사한다. 하나라도 걸리면 디스크에 아무것도 쓰지 않는다.
    Map<MultipartFile, String> verifiedTypes = new LinkedHashMap<>();
    for (MultipartFile f : targets) {
      verifiedTypes.put(f, ScanFileRule.verify(f));
    }

    Path dir = contractDir(cn);
    String uploader = session == null ? null
        : (String) session.getAttribute(AuthService.SESSION_LOGIN_ID);
    String title = blankToNull(docTitle);

    List<Path> written = new ArrayList<>();
    List<ContractScan> saved = new ArrayList<>();
    try {
      Files.createDirectories(dir);

      for (MultipartFile f : targets) {
        String original = StringUtils.cleanPath(
            Objects.requireNonNullElse(f.getOriginalFilename(), "scan"));
        String ext = ScanFileRule.extensionOf(original);
        String stored = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);

        Path target = dir.resolve(stored);
        Files.copy(f.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        written.add(target);

        saved.add(scanRepo.save(ContractScan.builder()
            .contractNumber(cn)
            .docCategory(category)
            .docTitle(title != null ? title : original)
            .docDate(docDate)
            .originalName(original)
            .storedName(stored)
            .contentType(verifiedTypes.get(f))
            .fileSize(f.getSize())
            .uploadedBy(uploader)
            .memo(blankToNull(memo))
            .build()));
      }
    } catch (IOException e) {
      // 쓰다가 끊기면 이미 쓴 파일을 치운다. DB 는 트랜잭션이 되돌린다.
      for (Path p : written) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
      }
      throw new IllegalArgumentException("파일을 저장하지 못했습니다: " + e.getMessage());
    }

    return saved.stream().map(ContractScanResponse::of).toList();
  }

  // ── 내려주기 · 지우기 ──────────────────────────────────────

  /** 파일 하나의 정보와 실제 경로. 경로가 보관 폴더 밖을 가리키면 거절한다. */
  @Transactional(readOnly = true)
  public StoredScan open(Long id) {
    ContractScan meta = scanRepo.findById(id)
        .orElseThrow(() -> new NotFoundException("스캔 서류를 찾을 수 없습니다. id=" + id));

    Path file = contractDir(meta.getContractNumber()).resolve(meta.getStoredName()).normalize();

    if (!file.startsWith(baseDir())) {
      throw new IllegalArgumentException("잘못된 파일 경로입니다.");
    }
    if (!Files.exists(file)) {
      throw new NotFoundException("파일이 서버에 없습니다: " + meta.getOriginalName());
    }
    return new StoredScan(meta, file);
  }

  @Transactional
  public void delete(Long id) {
    ContractScan meta = scanRepo.findById(id)
        .orElseThrow(() -> new NotFoundException("스캔 서류를 찾을 수 없습니다. id=" + id));

    Path file = contractDir(meta.getContractNumber()).resolve(meta.getStoredName()).normalize();
    if (file.startsWith(baseDir())) {
      try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }
    scanRepo.delete(meta);
  }

  public record StoredScan(ContractScan meta, Path file) { }

  // ── 내부 ──────────────────────────────────────────────────

  private String requireContract(String contractNumber) {
    String cn = contractNumber == null ? "" : contractNumber.trim();
    if (cn.isEmpty()) throw new IllegalArgumentException("계약번호가 비어 있습니다.");
    if (contractRepo.findByContractNumber(cn).isEmpty()) {
      throw new NotFoundException("계약번호를 찾을 수 없습니다: " + cn);
    }
    return cn;
  }

  private Path baseDir() {
    return Paths.get(scanUploadDir).toAbsolutePath().normalize();
  }

  /** uploads/scans/{회사DB}/{계약번호} */
  private Path contractDir(String contractNumber) {
    return baseDir().resolve(tenantFolder()).resolve(safeSegment(contractNumber));
  }

  /** 로그인한 세션의 회사 DB 이름. 폴더 이름으로 쓰므로 영숫자·밑줄만 남긴다. */
  private String tenantFolder() {
    String db = TenantContext.getCurrentDb();
    String folder = safeSegment(db == null || db.isBlank() ? "auth" : db);
    return folder.isEmpty() ? "auth" : folder;
  }

  private static String safeSegment(String s) {
    return s == null ? "" : s.replaceAll("[^A-Za-z0-9_\\-]", "");
  }

  private static String blankToNull(String s) {
    return (s == null || s.trim().isEmpty()) ? null : s.trim();
  }
}
