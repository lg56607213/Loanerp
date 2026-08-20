package com.jdend.erp.contract.service;

import com.jdend.erp.common.excel.ExcelReader;
import com.jdend.erp.common.excel.ExcelTemplateWriter;
import com.jdend.erp.common.excel.ExcelUploadResultResponse;
import com.jdend.erp.contract.dto.ContractRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.jdend.erp.common.excel.ExcelRowParsers.*;

/**
 * 여신계약 엑셀 일괄 업로드. 행마다 기존 {@link ContractService#create}를 그대로 재사용한다.
 * 이 서비스 자체는 트랜잭션을 걸지 않고, 다른 빈(ContractService)의 @Transactional 메서드를
 * 행마다 호출해 각 행이 독립된 트랜잭션이 되게 한다(한 행 실패가 다른 행에 영향 없음).
 */
@Service
@RequiredArgsConstructor
public class ContractBulkUploadService {

  private static final List<String> HEADERS = List.of(
      "고객번호", "고객구분", "대출구분", "대출금", "이자율", "연체이율", "연체이자부과",
      "상환방식", "실행일", "시작일자", "종료일자", "납입일자", "회차수", "비고"
  );

  private static final List<String> SAMPLE_ROW = List.of(
      "C001", "개인", "신용대출", "10000000", "19.9", "20", "Y",
      "원리금균등", "2026-01-01", "2026-01-01", "2027-12-31", "25", "24",
      "샘플 행입니다. 실제 데이터로 바꿔서 업로드하세요."
  );

  private final ContractService contractService;

  public byte[] template() {
    return ExcelTemplateWriter.write(HEADERS, SAMPLE_ROW);
  }

  public ExcelUploadResultResponse upload(MultipartFile file) {
    List<Map<String, String>> rows;
    try {
      rows = ExcelReader.readRows(file.getInputStream());
    } catch (Exception e) {
      throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
    }

    int success = 0;
    List<ExcelUploadResultResponse.RowError> errors = new ArrayList<>();

    for (int i = 0; i < rows.size(); i++) {
      int rowNumber = i + 2; // 1행=헤더
      try {
        contractService.create(toRequest(rows.get(i)));
        success++;
      } catch (Exception e) {
        errors.add(ExcelUploadResultResponse.RowError.builder()
            .rowNumber(rowNumber)
            .message(e.getMessage())
            .build());
      }
    }

    return ExcelUploadResultResponse.builder()
        .totalRows(rows.size())
        .successCount(success)
        .failCount(errors.size())
        .errors(errors)
        .build();
  }

  private ContractRequest toRequest(Map<String, String> row) {
    return ContractRequest.builder()
        .customerNumber(str(row, "고객번호"))
        .customerType(str(row, "고객구분"))
        .loanType(str(row, "대출구분"))
        .loanAmount(longVal(row, "대출금"))
        .interestRate(rateVal(row, "이자율"))
        .overdueRate(rateVal(row, "연체이율"))
        .overdueChargeYn(yesNo(row, "연체이자부과"))
        .repaymentMethod(str(row, "상환방식"))
        .executeDate(dateVal(row, "실행일"))
        .startDate(dateVal(row, "시작일자"))
        .endDate(dateVal(row, "종료일자"))
        .paymentDay(intVal(row, "납입일자"))
        .installmentCount(intVal(row, "회차수"))
        .remarks(str(row, "비고"))
        .build();
  }

  /** 이자율 셀 — "19.9" / "19.9%" 모두 허용 */
  private static BigDecimal rateVal(Map<String, String> row, String key) {
    String v = str(row, key);
    if (v == null || v.isBlank()) return null;
    try {
      return new BigDecimal(v.replace("%", "").trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " 값이 숫자가 아닙니다: " + v);
    }
  }

  /** 연체이자 부과 여부 — 미입력이면 부과(true) */
  private static Boolean yesNo(Map<String, String> row, String key) {
    String v = str(row, key);
    if (v == null || v.isBlank()) return Boolean.TRUE;
    String t = v.trim();
    return !("N".equalsIgnoreCase(t) || "아니오".equals(t) || "미부과".equals(t) || "false".equalsIgnoreCase(t));
  }
}
