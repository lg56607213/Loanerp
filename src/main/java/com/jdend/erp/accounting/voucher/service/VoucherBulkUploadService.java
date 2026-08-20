package com.jdend.erp.accounting.voucher.service;

import com.jdend.erp.accounting.voucher.dto.VoucherCreateRequest;
import com.jdend.erp.common.excel.ExcelReader;
import com.jdend.erp.common.excel.ExcelTemplateWriter;
import com.jdend.erp.common.excel.ExcelUploadResultResponse;
import com.jdend.erp.management.financial.entity.FinancialStatementAccount;
import com.jdend.erp.management.financial.repository.FinancialStatementAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.*;

import static com.jdend.erp.common.excel.ExcelRowParsers.*;

/**
 * 전표 엑셀 일괄 업로드.
 *
 * <p><b>열 구성</b><br>
 * 전표일자 / 전표번호 / 차변계정코드 / 차변금액 / 차변적요 / 대변계정코드 / 대변금액 / 대변적요
 *
 * <p><b>그룹핑 규칙</b><br>
 * "전표번호"를 입력하면 같은 전표일자 + 같은 전표번호의 행들이 하나의 전표로 묶인다(복합전표 작성 시 사용).
 * "전표번호"를 비워두면 해당 행 혼자 독립 전표가 되며, 전표번호는 자동채번된다.
 *
 * <p>묶은 그룹 안에서 차변 합계와 대변 합계가 같으면 정상, 다르면 그 그룹 전체를 오류로 보고한다.
 */
@Service
@RequiredArgsConstructor
public class VoucherBulkUploadService {

  private static final List<String> HEADERS = List.of(
      "전표일자", "전표번호", "차변계정코드", "차변금액", "차변적요", "대변계정코드", "대변금액", "대변적요"
  );

  private static final List<List<String>> SAMPLE_ROWS = List.of(
      // 1:1 단순전표 (전표번호 공란 → 자동채번, 한 행에 차변+대변 모두)
      List.of("2026-06-25", "", "100101", "1000000", "보통예금 입금", "400101", "1000000", "이자수익"),
      List.of("2026-06-25", "", "100101", "55000",   "보통예금 입금", "200101", "55000",   "미지급금 상환"),
      // 전표번호로 행을 묶는 복합전표 (차변1 : 대변2) → 전표번호 V001 두 행이 하나의 전표
      List.of("2026-06-26", "V001", "100101", "1300000", "보통예금 입금", "100502", "977214", "대여금 원금회수"),
      List.of("2026-06-26", "V001", "",        "",         "",             "400101", "322786", "이자수익")
  );

  /** application.yml의 erp.voucher.bulk-upload.max-rows 값으로 재정의 가능. 기본 3000행. */
  @Value("${erp.voucher.bulk-upload.max-rows:3000}")
  private int maxBulkUploadRows;

  private final VoucherService voucherService;
  private final FinancialStatementAccountRepository accountRepo;

  public byte[] template() {
    return ExcelTemplateWriter.writeMultiRow(HEADERS, SAMPLE_ROWS);
  }

  /** 파일을 파싱·검증만 하고 DB에는 저장하지 않는다. 사용자가 결과를 확인 후 upload()로 실제 저장한다. */
  public ExcelUploadResultResponse preview(MultipartFile file) {
    List<Map<String, String>> rows = readAndValidateFile(file);
    LinkedHashMap<String, List<Integer>> groups = buildGroups(rows);

    int success = 0;
    List<ExcelUploadResultResponse.RowError> errors = new ArrayList<>();

    for (List<Integer> indices : groups.values()) {
      List<Integer> rowNumbers = indices.stream().map(i -> i + 2).toList();
      try {
        toRequest(rows, indices); // 검증만, DB 저장 없음
        success += indices.size();
      } catch (Exception e) {
        errors.add(buildRowError(rowNumbers, e.getMessage()));
      }
    }

    return ExcelUploadResultResponse.builder()
        .totalRows(rows.size()).successCount(success)
        .failCount(rows.size() - success).errors(errors).build();
  }

  /** preview() 후 사용자가 확인하면 호출. 검증 통과한 전표를 DB에 저장한다. */
  public ExcelUploadResultResponse upload(MultipartFile file) {
    List<Map<String, String>> rows = readAndValidateFile(file);
    LinkedHashMap<String, List<Integer>> groups = buildGroups(rows);

    int success = 0;
    List<ExcelUploadResultResponse.RowError> errors = new ArrayList<>();

    for (List<Integer> indices : groups.values()) {
      List<Integer> rowNumbers = indices.stream().map(i -> i + 2).toList();
      try {
        voucherService.create(toRequest(rows, indices));
        success += indices.size();
      } catch (Exception e) {
        errors.add(buildRowError(rowNumbers, e.getMessage()));
      }
    }

    return ExcelUploadResultResponse.builder()
        .totalRows(rows.size()).successCount(success)
        .failCount(rows.size() - success).errors(errors).build();
  }

  private List<Map<String, String>> readAndValidateFile(MultipartFile file) {
    List<Map<String, String>> rows;
    try {
      rows = ExcelReader.readRows(file.getInputStream());
    } catch (Exception e) {
      throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
    }
    if (rows.size() > maxBulkUploadRows) {
      throw new IllegalArgumentException(
          "한 번에 업로드 가능한 최대 행 수는 " + maxBulkUploadRows + "개입니다. " +
          "현재 파일의 데이터 행: " + rows.size() + "개. 연도별로 나눠서 여러 번 업로드해 주세요.");
    }
    return rows;
  }

  private LinkedHashMap<String, List<Integer>> buildGroups(List<Map<String, String>> rows) {
    LinkedHashMap<String, List<Integer>> groups = new LinkedHashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      int rowNumber = i + 2;
      Map<String, String> row = rows.get(i);
      String voucherNo = str(row, "전표번호");
      LocalDate rowDate = null;
      try { rowDate = dateVal(row, "전표일자"); } catch (Exception ignored) {}

      String key = (voucherNo != null && rowDate != null)
          ? "G:" + rowDate + ":" + voucherNo
          : "R:" + rowNumber;
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
    }
    return groups;
  }

  private ExcelUploadResultResponse.RowError buildRowError(List<Integer> rowNumbers, String message) {
    String rowLabel = rowNumbers.size() == 1
        ? (rowNumbers.get(0) + "행")
        : (rowNumbers.get(0) + "~" + rowNumbers.get(rowNumbers.size() - 1) + "행");
    return ExcelUploadResultResponse.RowError.builder()
        .rowNumber(rowNumbers.get(0)).message(rowLabel + ": " + message).build();
  }

  private VoucherCreateRequest toRequest(List<Map<String, String>> rows, List<Integer> indices) {
    LocalDate voucherDate = null;

    List<VoucherCreateRequest.VoucherLineRequest> debitEntries  = new ArrayList<>();
    List<VoucherCreateRequest.VoucherLineRequest> creditEntries = new ArrayList<>();

    for (int i : indices) {
      Map<String, String> row = rows.get(i);

      LocalDate rowDate = dateVal(row, "전표일자");
      if (rowDate == null) {
        throw new IllegalArgumentException("전표일자는 필수입니다.");
      }
      if (voucherDate == null) {
        voucherDate = rowDate;
      } else if (!voucherDate.equals(rowDate)) {
        throw new IllegalArgumentException("같은 전표번호로 묶인 행들의 전표일자가 서로 다릅니다.");
      }

      String debitCode    = str(row, "차변계정코드");
      Long   debitAmount  = longVal(row, "차변금액");
      String debitDesc    = str(row, "차변적요");
      boolean hasDebit    = debitCode != null && debitAmount != null && debitAmount > 0;

      String creditCode   = str(row, "대변계정코드");
      Long   creditAmount = longVal(row, "대변금액");
      String creditDesc   = str(row, "대변적요");
      boolean hasCredit   = creditCode != null && creditAmount != null && creditAmount > 0;

      if (!hasDebit && !hasCredit) {
        throw new IllegalArgumentException(
            "차변계정코드+차변금액 또는 대변계정코드+대변금액 중 하나 이상을 입력해주세요.");
      }

      if (hasDebit) {
        debitEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .accountCode(debitCode).account(resolveAccountName(debitCode))
            .amount(debitAmount).description(debitDesc).build());
      }
      if (hasCredit) {
        creditEntries.add(VoucherCreateRequest.VoucherLineRequest.builder()
            .accountCode(creditCode).account(resolveAccountName(creditCode))
            .amount(creditAmount).description(creditDesc).build());
      }
    }

    long debitSum  = debitEntries.stream().mapToLong(VoucherCreateRequest.VoucherLineRequest::getAmount).sum();
    long creditSum = creditEntries.stream().mapToLong(VoucherCreateRequest.VoucherLineRequest::getAmount).sum();

    if (debitSum != creditSum) {
      throw new IllegalArgumentException(
          "차변 합계(" + debitSum + ")와 대변 합계(" + creditSum + ")가 일치하지 않습니다.");
    }

    return VoucherCreateRequest.builder()
        .voucherDate(voucherDate)
        .debitEntries(debitEntries)
        .creditEntries(creditEntries)
        .build();
  }

  private String resolveAccountName(String accountCode) {
    if (accountCode == null) {
      throw new IllegalArgumentException("계정코드는 필수입니다.");
    }

    FinancialStatementAccount account = accountRepo.findByAccountCode(accountCode)
        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 계정코드입니다: " + accountCode));

    if (!"사용".equals(account.getIsPostable())) {
      throw new IllegalArgumentException("전표에 사용할 수 없는 계정코드입니다(전기가능=미사용): " + accountCode);
    }

    return account.getAccountName();
  }
}
