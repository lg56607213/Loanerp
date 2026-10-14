package com.jdend.erp.payment.payment.service;

import com.jdend.erp.common.excel.ExcelReader;
import com.jdend.erp.common.excel.ExcelTemplateWriter;
import com.jdend.erp.common.excel.ExcelUploadResultResponse;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.payment.payment.dto.PaymentUpsertRequest;
import com.jdend.erp.payment.payment.support.PaymentSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.jdend.erp.common.excel.ExcelRowParsers.dateVal;
import static com.jdend.erp.common.excel.ExcelRowParsers.longVal;
import static com.jdend.erp.common.excel.ExcelRowParsers.str;

/**
 * 수납 엑셀 일괄등록.
 *
 * 지금까지 쌓인 수납 내역을 한 번에 옮겨 적을 때 쓴다. 그 시기 장부는 이미
 * 마감돼 있으므로 전표는 보통 만들지 않는다 — 업로드 단위로 고르게 한다.
 *
 * 올리기 전에 먼저 검사만 해 볼 수 있다(dryRun). 몇 건이 되고 어느 행이
 * 왜 걸리는지 보고 나서 올릴지 정하는 편이, 반쯤 들어간 데이터를 되돌리는
 * 것보다 낫다.
 */
@Service
@RequiredArgsConstructor
public class PaymentBulkUploadService {

  private static final List<String> HEADERS = List.of(
      "계약번호", "입금일자", "입금금액", "당사계좌", "충당기준일", "초과금처리", "수납재원", "비고"
  );

  private static final List<String> SAMPLE_ROW = List.of(
      "CRD260101001001", "20230821", "2007670", "주거래통장 새마을금고 9002-2082-9219-3",
      "", "원금충당", "보통예금",
      "샘플 행입니다. 지우고 실제 데이터를 넣으세요. 날짜는 8자리 숫자(20230821)로 적습니다. 충당기준일은 비우면 입금일자를 씁니다."
  );

  private final PaymentService paymentService;
  private final ContractRepository contractRepo;

  public byte[] template() {
    return ExcelTemplateWriter.write(HEADERS, SAMPLE_ROW);
  }

  /**
   * @param createVoucher 전표를 만들지 여부. 과거 내역을 옮길 때는 false.
   * @param dryRun true 면 저장하지 않고 검사만 한다.
   */
  public ExcelUploadResultResponse upload(MultipartFile file, boolean createVoucher, boolean dryRun) {
    List<Map<String, String>> rows;
    try {
      rows = ExcelReader.readRows(file.getInputStream());
    } catch (Exception e) {
      throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
    }

    int success = 0;
    List<ExcelUploadResultResponse.RowError> errors = new ArrayList<>();

    for (int i = 0; i < rows.size(); i++) {
      int rowNumber = i + 2;                     // 1행은 헤더
      try {
        PaymentUpsertRequest req = toRequest(rows.get(i), createVoucher);
        if (dryRun) {
          validateOnly(req);
        } else {
          paymentService.create(req);
        }
        success++;
      } catch (Exception e) {
        String msg = e.getMessage();
        errors.add(ExcelUploadResultResponse.RowError.builder()
            .rowNumber(rowNumber)
            .message(msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg)
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
   * 저장하지 않고 걸릴 만한 것만 미리 본다.
   *
   * 충당 결과까지는 보지 않는다. 그건 앞 행이 저장돼야 정해지는 값이라,
   * 저장 없이 미리 맞히면 실제와 어긋난 숫자를 보여주게 된다.
   */
  private void validateOnly(PaymentUpsertRequest req) {
    if (req.getContractNumber() == null || req.getContractNumber().isBlank()) {
      throw new IllegalArgumentException("계약번호가 비어 있습니다.");
    }
    if (contractRepo.findByContractNumber(req.getContractNumber().trim()).isEmpty()) {
      throw new IllegalArgumentException("계약번호를 찾을 수 없습니다: " + req.getContractNumber());
    }
    if (req.getPaymentDate() == null) {
      throw new IllegalArgumentException("입금일자가 비어 있거나 날짜가 아닙니다. 8자리 숫자로 적으세요. 예: 20260630");
    }
    if (req.getPaymentAmount() == null || req.getPaymentAmount() <= 0) {
      throw new IllegalArgumentException("입금금액은 1 이상이어야 합니다.");
    }
    if (PaymentSource.isPrepaid(req.getPaymentSource())
        && (req.getCompanyAccount() != null && !req.getCompanyAccount().isBlank())) {
      // 막을 일은 아니지만 알려는 준다 — 선수금 수납은 통장에서 나온 돈이 아니다
      throw new IllegalArgumentException("수납재원이 '선수금'인데 당사계좌가 적혀 있습니다. 계좌를 비우세요.");
    }
  }

  private PaymentUpsertRequest toRequest(Map<String, String> row, boolean createVoucher) {
    String source = PaymentSource.orDefault(str(row, "수납재원"));

    return PaymentUpsertRequest.builder()
        .contractNumber(str(row, "계약번호"))
        .paymentDate(dateVal(row, "입금일자"))
        .paymentAmount(longVal(row, "입금금액"))
        .companyAccount(blankToNull(str(row, "당사계좌")))
        .applyDate(applyDate(row))
        .surplusPolicy(blankToNull(str(row, "초과금처리")))
        .paymentSource(source)
        .memo(blankToNull(str(row, "비고")))
        .createVoucher(createVoucher)
        .build();
  }

  /** 충당기준일 — 비우면 입금일자를 쓴다(수납 화면과 같다). */
  private static LocalDate applyDate(Map<String, String> row) {
    String v = str(row, "충당기준일");
    return (v == null || v.isBlank()) ? null : dateVal(row, "충당기준일");
  }

  private static String blankToNull(String s) {
    return (s == null || s.trim().isEmpty()) ? null : s.trim();
  }
}
