package com.jdend.erp.customer;

import com.jdend.erp.common.excel.ExcelReader;
import com.jdend.erp.common.excel.ExcelTemplateWriter;
import com.jdend.erp.common.excel.ExcelUploadResultResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jdend.erp.common.excel.ExcelRowParsers.str;

/**
 * 고객 엑셀 일괄 업로드. 고객번호는 항상 자동 채번(CustomerController와 동일한 규칙)한다.
 * 각 행을 별도 트랜잭션으로 저장해 한 행 실패가 다른 행에 영향을 주지 않게 한다.
 *
 * <p>사업자등록번호(개인은 주민등록번호)는 고객을 가리키는 열쇠라 DB 에 유니크 제약이
 * 걸려 있다. 저장해 보고 제약에 걸리는 식으로 알면 화면에 SQL 오류문이 그대로 나와
 * 무슨 일인지 알 수 없으므로, 저장 전에 미리 보고 사람이 읽을 수 있는 말로 알려 준다.
 *
 * <p>겹치는 자리는 둘이다 — 이미 등록된 고객과 겹치는 경우, 그리고 올린 파일 안에서
 * 같은 번호가 두 번 나오는 경우. 뒤쪽은 DB 를 아무리 봐도 알 수 없어 따로 센다.
 */
@Service
@RequiredArgsConstructor
public class CustomerBulkUploadService {

  private static final List<String> HEADERS = List.of(
      "고객구분", "고객명", "사업자등록번호", "전화번호", "대표자",
      "업태", "업종", "주소", "담당자", "담당자전화", "담당자이메일"
  );

  private static final List<String> SAMPLE_ROW = List.of(
      "법인", "주식회사 샘플", "123-45-67890", "02-1234-5678", "홍길동",
      "서비스업", "렌탈업", "서울특별시 강남구", "김담당", "010-1234-5678", "sample@example.com"
  );

  private final CustomerRepository repo;
  private final CustomerNumberGenerator numberGenerator;

  public byte[] template() {
    return ExcelTemplateWriter.write(HEADERS, SAMPLE_ROW);
  }

  public ExcelUploadResultResponse upload(MultipartFile file) {
    return upload(file, false);
  }

  /**
   * @param dryRun true 면 저장하지 않고 검사만 한다. 무엇이 걸리는지 먼저 보여준 뒤
   *               사용자가 저장을 누르면 false 로 다시 부른다.
   */
  public ExcelUploadResultResponse upload(MultipartFile file, boolean dryRun) {
    List<Map<String, String>> rows;
    try {
      rows = ExcelReader.readRows(file.getInputStream());
    } catch (Exception e) {
      throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
    }

    int success = 0;
    List<ExcelUploadResultResponse.RowError> errors = new ArrayList<>();

    // 사업자등록번호 → 그 번호가 먼저 나온 행번호. 파일 안의 중복을 잡는다.
    Map<String, Integer> seenInFile = new HashMap<>();

    for (int i = 0; i < rows.size(); i++) {
      int rowNumber = i + 2;                      // 1행은 머리글
      Map<String, String> row = rows.get(i);
      try {
        validate(row, rowNumber, seenInFile);
        if (!dryRun) createOne(row);
        success++;
      } catch (Exception e) {
        errors.add(ExcelUploadResultResponse.RowError.builder()
            .rowNumber(rowNumber)
            .message(readable(e, row))
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

  /**
   * 저장하기 전에 걸릴 것을 미리 본다.
   *
   * @param seenInFile 앞선 행에서 이미 나온 사업자등록번호. 이 메서드가 채운다.
   */
  private void validate(Map<String, String> row, int rowNumber, Map<String, Integer> seenInFile) {
    String customerType = str(row, "고객구분");
    String customerName = str(row, "고객명");
    String registrationNumber = str(row, "사업자등록번호");

    if (customerType == null) throw new IllegalArgumentException("고객구분은 필수입니다.");
    if (customerName == null) throw new IllegalArgumentException("고객명은 필수입니다.");
    if (registrationNumber == null) throw new IllegalArgumentException("사업자등록번호는 필수입니다.");

    Integer firstRow = seenInFile.putIfAbsent(registrationNumber, rowNumber);
    if (firstRow != null) {
      throw new IllegalArgumentException(
          "이 파일 안에서 중복된 사업자등록번호입니다: " + registrationNumber
          + " (" + firstRow + "행과 같습니다)");
    }

    Optional<Customer> already = repo.findByRegistrationNumber(registrationNumber);
    if (already.isPresent()) {
      Customer c = already.get();
      throw new IllegalArgumentException(
          "이미 등록된 사업자등록번호입니다: " + registrationNumber
          + " (" + c.getCustomerName() + " · 고객번호 " + c.getCustomerNumber() + ")"
          + " — 이미 올린 파일이라면 다시 올리지 않아도 됩니다.");
    }
  }

  /**
   * 걸러 낸 뒤에도 유니크 제약에 걸릴 수 있다. 미리 보고 저장하기 사이에 다른 사람이
   * 같은 고객을 등록했을 때다. 드문 일이지만 그때도 SQL 오류문이 아니라 말로 알려 준다.
   */
  private String readable(Exception e, Map<String, String> row) {
    if (e instanceof DataIntegrityViolationException || isDuplicateKey(e)) {
      String reg = str(row, "사업자등록번호");
      return "이미 등록된 사업자등록번호입니다" + (reg == null ? "." : ": " + reg)
           + " — 저장하는 사이에 같은 고객이 등록된 것 같습니다. 조회해 보고 다시 올리세요.";
    }
    String msg = e.getMessage();
    return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
  }

  private boolean isDuplicateKey(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String m = t.getMessage();
      if (m != null && m.contains("Duplicate entry")) return true;
      if (t.getCause() == t) break;
    }
    return false;
  }

  public void createOne(Map<String, String> row) {
    String customerType = str(row, "고객구분");
    String customerName = str(row, "고객명");
    String registrationNumber = str(row, "사업자등록번호");

    if (customerType == null) throw new IllegalArgumentException("고객구분은 필수입니다.");
    if (customerName == null) throw new IllegalArgumentException("고객명은 필수입니다.");
    if (registrationNumber == null) throw new IllegalArgumentException("사업자등록번호는 필수입니다.");

    Customer c = Customer.builder()
        .customerNumber(numberGenerator.next())
        .customerType(customerType)
        .customerName(customerName)
        .registrationNumber(registrationNumber)
        .phone(str(row, "전화번호"))
        .ceo(str(row, "대표자"))
        .businessType(str(row, "업태"))
        .businessItem(str(row, "업종"))
        .address(str(row, "주소"))
        .manager(str(row, "담당자"))
        .managerPhone(str(row, "담당자전화"))
        .managerEmail(str(row, "담당자이메일"))
        .registerDate(LocalDate.now())
        .build();

    repo.save(c);
  }
}
