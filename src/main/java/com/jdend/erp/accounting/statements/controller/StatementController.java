package com.jdend.erp.accounting.statements.controller;

import com.jdend.erp.accounting.statements.dto.*;
import com.jdend.erp.accounting.statements.service.StatementService;
import com.jdend.erp.common.excel.ExcelExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/statements")

public class StatementController {

  private final StatementService service;
  private final ExcelExportService excelExportService;

  // GET /api/statements/income?startDate=2026-01-01&endDate=2026-03-03&status=승인
  @GetMapping("/income")
  public IncomeStatementResponse income(
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate,
      @RequestParam(required = false) String status
  ) {
    return service.income(startDate, endDate, defaultStatus(status));
  }

  // GET /api/statements/balance?referenceDate=2026-03-03&status=승인
  @GetMapping("/balance")
  public BalanceSheetResponse balance(
      @RequestParam LocalDate referenceDate,
      @RequestParam(required = false) String status
  ) {
    return service.balance(referenceDate, defaultStatus(status));
  }

  // GET /api/statements/balance/details?accountCode=1001&referenceDate=2026-04-20&status=승인
  // startDate를 같이 주면 그 기간(startDate~referenceDate) 내 전표만 조회한다(손익계산서 상세용).
  @GetMapping("/balance/details")
  public List<BalanceDetailRowResponse> balanceDetails(
      @RequestParam String accountCode,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam LocalDate referenceDate,
      @RequestParam(required = false) String status
  ) {
    return service.balanceDetails(accountCode, startDate, referenceDate, defaultStatus(status));
  }

  // GET /api/statements/balance/details/export — 단일 계정 상세내역 엑셀 다운로드
  @GetMapping("/balance/details/export")
  public ResponseEntity<byte[]> balanceDetailsExport(
      @RequestParam String accountCode,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam LocalDate referenceDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "계정") String accountName
  ) {
    List<BalanceDetailRowResponse> rows = service.balanceDetails(accountCode, startDate, referenceDate, defaultStatus(status));
    String[] headers = {"전표일자", "전표번호", "구분", "계정명", "금액", "적요", "계약번호", "메모", "상태"};
    List<Object[]> data = rows.stream().map(r -> new Object[]{
        r.getVoucherDate(), r.getVoucherNo(),
        "DEBIT".equals(r.getLineType()) ? "차변" : "대변",
        r.getAccountName(), r.getAmount(), r.getDescription(),
        r.getContractNumber(), r.getMemo(), r.getStatus()
    }).collect(Collectors.toList());

    byte[] excel = excelExportService.build(accountName, headers, data);
    String encodedName1 = URLEncoder.encode(accountName + "_" + referenceDate + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName1)
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(excel);
  }

  // GET /api/statements/balance/export — 재무상태표 전체(자산+부채+자본) 상세내역 엑셀 다운로드
  @GetMapping("/balance/export")
  public ResponseEntity<byte[]> balanceExport(
      @RequestParam LocalDate referenceDate,
      @RequestParam(required = false) String status
  ) {
    String[] headers = {"계정구분", "전표일자", "전표번호", "구분", "계정명", "금액", "적요", "계약번호", "메모", "상태"};
    List<Object[]> data = new ArrayList<>();

    String[][] categories = {{"10", "자산"}, {"20", "부채"}, {"30", "자본"}};
    for (String[] cat : categories) {
      List<BalanceDetailRowResponse> rows = service.balanceDetails(cat[0], null, referenceDate, status);
      for (BalanceDetailRowResponse r : rows) {
        data.add(new Object[]{
            cat[1], r.getVoucherDate(), r.getVoucherNo(),
            "DEBIT".equals(r.getLineType()) ? "차변" : "대변",
            r.getAccountName(), r.getAmount(), r.getDescription(),
            r.getContractNumber(), r.getMemo(), r.getStatus()
        });
      }
    }

    byte[] excel = excelExportService.build("재무상태표", headers, data);
    String encodedName2 = URLEncoder.encode("재무상태표_" + referenceDate + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName2)
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(excel);
  }

  // GET /api/statements/income/export — 손익계산서 전체(수익+비용) 상세내역 엑셀 다운로드
  @GetMapping("/income/export")
  public ResponseEntity<byte[]> incomeExport(
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate,
      @RequestParam(required = false) String status
  ) {
    String[] headers = {"계정구분", "전표일자", "전표번호", "구분", "계정명", "금액", "적요", "계약번호", "메모", "상태"};
    List<Object[]> data = new ArrayList<>();

    String[][] categories = {{"40", "수익"}, {"50", "비용"}};
    for (String[] cat : categories) {
      List<BalanceDetailRowResponse> rows = service.balanceDetails(cat[0], startDate, endDate, status);
      for (BalanceDetailRowResponse r : rows) {
        data.add(new Object[]{
            cat[1], r.getVoucherDate(), r.getVoucherNo(),
            "DEBIT".equals(r.getLineType()) ? "차변" : "대변",
            r.getAccountName(), r.getAmount(), r.getDescription(),
            r.getContractNumber(), r.getMemo(), r.getStatus()
        });
      }
    }

    byte[] excel = excelExportService.build("손익계산서", headers, data);
    String encodedName3 = URLEncoder.encode("손익계산서_" + startDate + "_" + endDate + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName3)
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(excel);
  }

  /**
   * 상태 필터 기본값.
   *
   * 파라미터를 아예 안 주면 승인분만 본다(재무제표의 기본). 빈 값으로 주면
   * '전체'라는 뜻이라 그대로 넘긴다.
   *
   * 전에는 @RequestParam 의 defaultValue 를 썼는데, 스프링은 값이 빈 문자열일
   * 때도 기본값으로 바꿔 버린다. 그래서 화면에서 '전체'를 골라도 승인분만
   * 나왔고, 대기 전표가 왜 안 보이는지 알 수 없었다.
   */
  private static String defaultStatus(String status) {
    return status == null ? "승인" : status;
  }

  /**
   * 재무제표에 반영되지 않는 전표 라인 점검.
   * 전표는 쳤는데 재무제표에서 금액이 비는 건을 찾아 이유까지 알려준다.
   */
  @GetMapping("/unreflected")
  public List<UnreflectedLineResponse> unreflected(
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate,
      @RequestParam(required = false) String status
  ) {
    return service.unreflected(startDate, endDate, defaultStatus(status));
  }
}