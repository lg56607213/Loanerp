package com.jdend.erp.contract.scan.controller;

import com.jdend.erp.auth.service.PermissionService;
import com.jdend.erp.contract.scan.dto.ContractScanResponse;
import com.jdend.erp.contract.scan.dto.ScanContractRowResponse;
import com.jdend.erp.contract.scan.service.ContractScanService;
import com.jdend.erp.contract.scan.support.ScanDocCategory;
import com.jdend.erp.contract.scan.support.ScanFileRule;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 채권관리 &gt; 스캔관리 — 계약서·추심관련서류·법적절차서류 보관 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/contract-scans")
public class ContractScanController {

  private final ContractScanService service;
  private final PermissionService permissionService;

  /** 화면이 쓰는 서류구분·허용형식. 양쪽에 같은 목록을 적어 두면 한쪽만 바뀐다. */
  @GetMapping("/meta")
  public Map<String, Object> meta() {
    return Map.of(
        "categories", ScanDocCategory.ALL,
        "extensions", ScanFileRule.ALLOWED_EXTENSIONS,
        "maxSize", ScanFileRule.MAX_SIZE,
        "allowedLabel", ScanFileRule.ALLOWED_LABEL
    );
  }

  /** 왼쪽 채권 목록 — 구분별 스캔 건수까지 */
  @GetMapping("/contracts")
  public List<ScanContractRowResponse> contracts(
      @RequestParam(required = false) String kw,
      @RequestParam(required = false, defaultValue = "false") boolean withScanOnly
  ) {
    return service.contracts(kw, withScanOnly);
  }

  /** 한 채권의 스캔 목록 */
  @GetMapping
  public List<ContractScanResponse> list(
      @RequestParam String contractNumber,
      @RequestParam(required = false) String category
  ) {
    return service.list(contractNumber, category);
  }

  /** 스캔 등록 — 여러 장을 한 번에 */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public List<ContractScanResponse> upload(
      @RequestParam String contractNumber,
      @RequestParam String docCategory,
      @RequestParam(required = false) String docTitle,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate docDate,
      @RequestParam(required = false) String memo,
      @RequestParam("files") List<MultipartFile> files,
      HttpSession session
  ) {
    return service.upload(contractNumber, docCategory, docTitle, docDate, memo, files, session);
  }

  /**
   * 파일 보기 · 내려받기.
   *
   * <p>download=false 면 브라우저 안에서 바로 열린다(inline). 서류를 확인만 하는 일이
   * 대부분이라 그때마다 내려받아 쌓이지 않게 한다.
   *
   * <p>올린 파일을 같은 도메인에서 내려주므로, 브라우저가 형식을 제멋대로 추측하지
   * 않도록 nosniff 를 붙인다. 캐시도 남기지 않는다 — 채무자 서류다.
   */
  @GetMapping("/{id:\\d+}/file")
  public ResponseEntity<Resource> file(
      @PathVariable Long id,
      @RequestParam(required = false, defaultValue = "false") boolean download
  ) {
    ContractScanService.StoredScan s = service.open(id);
    String name = s.meta().getOriginalName();

    ContentDisposition disposition = (download
        ? ContentDisposition.attachment()
        : ContentDisposition.inline())
        .filename(name, StandardCharsets.UTF_8)
        .build();

    MediaType type = MediaType.APPLICATION_OCTET_STREAM;
    if (s.meta().getContentType() != null && !s.meta().getContentType().isBlank()) {
      try { type = MediaType.parseMediaType(s.meta().getContentType()); } catch (Exception ignored) { }
    }

    HttpHeaders headers = new HttpHeaders();
    headers.setContentDisposition(disposition);
    headers.add("X-Content-Type-Options", "nosniff");
    headers.setCacheControl("private, no-store");

    FileSystemResource body = new FileSystemResource(s.file().toFile());
    return ResponseEntity.ok()
        .headers(headers)
        .contentType(type)
        .contentLength(body.getFile().length())
        .body(body);
  }

  /** 삭제는 책임자만 — 올린 서류는 추심·소송에서 근거로 쓰인다. */
  @DeleteMapping("/{id:\\d+}")
  public void delete(@PathVariable Long id, HttpSession session) {
    permissionService.requireManager(session);
    service.delete(id);
  }
}
