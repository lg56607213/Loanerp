package com.jdend.erp.contract.service;

import com.jdend.erp.common.excel.ExcelReader;
import com.jdend.erp.common.excel.ExcelTemplateWriter;
import com.jdend.erp.common.excel.ExcelUploadResultResponse;
import com.jdend.erp.contract.dto.ContractRequest;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.customer.CustomerRepository;
import com.jdend.erp.contract.entity.PaymentDayType;
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
      "고객번호", "고객구분", "고객명", "대출구분", "대출금", "이자율", "연체이율", "연체이자부과",
      "상환방식", "실행일", "시작일자", "종료일자", "납입일자", "회차수", "전표발생", "비고"
  );

  private static final List<String> SAMPLE_ROW = List.of(
      "C001", "개인", "홍길동", "신용대출", "10000000", "19.9", "20", "Y",
      "원리금균등", "20260101", "20260101", "20271231", "25", "24", "Y",
      "샘플 행입니다. 실제 데이터로 바꿔서 업로드하세요. 날짜는 8자리 숫자(20260101)로 적습니다."
          + " 고객번호를 모르면 비우고 고객명만 적어도 됩니다(같은 이름이 둘 이상이면 고객번호가 필요합니다)."
          + " 납입일자는 숫자(25) 또는 '말일' 로 적습니다."
          + " 전표발생은 Y/N 입니다. 그 시기 전표를 이미 따로 올렸으면 N 으로 두세요(비우면 Y)."
  );

  private final ContractService contractService;
  private final CustomerRepository customerRepo;

  public byte[] template() {
    return ExcelTemplateWriter.write(HEADERS, SAMPLE_ROW);
  }

  public ExcelUploadResultResponse upload(MultipartFile file) {
    return upload(file, false);
  }

  /**
   * @param dryRun true 면 저장하지 않고 검사만 한다. 대출은 한 행이 채권·스케줄·전표를
   *               한꺼번에 만들기 때문에, 잘못 올리고 지우는 것보다 미리 보는 편이 낫다.
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

    for (int i = 0; i < rows.size(); i++) {
      int rowNumber = i + 2; // 1행=헤더
      try {
        if (dryRun) {
          contractService.validateOnly(toRequest(rows.get(i)));
        } else {
          contractService.create(toRequest(rows.get(i)));
        }
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
        .customerNumber(resolveCustomerNumber(row))
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
        .paymentDay(paymentDayOf(row))
        .paymentDayType(paymentDayTypeOf(row))
        .installmentCount(intVal(row, "회차수"))
        .createVoucher(createVoucherOf(row))
        .remarks(str(row, "비고"))
        .build();
  }

  /**
   * 전표발생 셀 — 대출 실행 전표를 만들지 여부.
   *
   * <p>그 시기 전표를 전표 일괄업로드로 이미 올린 뒤 대출을 옮겨 적는 일이 많다.
   * 그때 전표까지 또 만들면 같은 실행 건이 두 번 적혀 재무상태표의 대여금과
   * 보통예금이 두 배로 잡힌다.
   *
   * <p>비우면 만든다 — 지금까지와 같고, 평소 등록에서는 전표가 나와야 한다.
   */
  private static Boolean createVoucherOf(Map<String, String> row) {
    String v = str(row, "전표발생");
    if (v == null || v.isBlank()) return Boolean.TRUE;

    String t = v.trim();
    if (t.equalsIgnoreCase("N") || t.equals("아니오") || t.equals("미발생")
        || t.equals("발생안함") || t.equalsIgnoreCase("false") || t.equals("0")) {
      return Boolean.FALSE;
    }
    if (t.equalsIgnoreCase("Y") || t.equals("예") || t.equals("발생")
        || t.equalsIgnoreCase("true") || t.equals("1")) {
      return Boolean.TRUE;
    }
    throw new IllegalArgumentException("전표발생은 Y 또는 N 으로 적으세요. 적힌 값: " + v);
  }

  /**
   * 납입일자 셀 — 숫자 외에 '말일' 도 받는다.
   *
   * <p>달마다 말일이 28·29·30·31일로 달라져서 숫자 하나로는 적을 수 없다. 화면에서는
   * 목록에서 '말일' 을 고르지만 엑셀에는 적을 자리가 없어 전부 오류로 떨어졌다.
   *
   * <p>받는 표기 — <b>말일</b>, <b>매월말일</b>, <b>32</b>(달력에 없는 날을 말일 표시로
   * 쓰는 관행), 그리고 <b>25일</b> 처럼 '일' 을 붙여 적은 숫자.
   *
   * @return 말일이면 null(그 달의 마지막 날을 쓰므로 숫자를 두지 않는다)
   */
  private static Integer paymentDayOf(Map<String, String> row) {
    String v = str(row, "납입일자");
    if (v == null || v.isBlank()) return null;
    if (isLastDayMark(v)) return null;

    String digits = v.trim().replace("일", "").replace(" ", "");
    try {
      return Integer.valueOf(digits);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "납입일자는 1~31 사이의 숫자이거나 '말일' 이어야 합니다. 적힌 값: " + v);
    }
  }

  private static String paymentDayTypeOf(Map<String, String> row) {
    String v = str(row, "납입일자");
    return (v != null && isLastDayMark(v)) ? PaymentDayType.LAST_DAY : PaymentDayType.DAY;
  }

  /** '말일' 로 볼 표기인가 */
  private static boolean isLastDayMark(String v) {
    String t = v.trim().replace(" ", "");
    return t.equals("말일") || t.equals("매월말일") || t.equals("말") || t.equals("32");
  }

  /**
   * 어느 고객인지 정한다 — 고객번호가 있으면 그것으로, 없으면 고객명으로 찾는다.
   *
   * <p>내려받은 자료에는 고객번호가 없고 이름만 있는 경우가 흔하다. 이름만 적어도
   * 올릴 수 있어야 쓸모가 있다. 다만 같은 이름이 둘 이상이면 어느 쪽인지 ERP 가
   * 고를 수 없으므로 고객번호를 달라고 한다 — 엉뚱한 사람에게 대출이 붙는 것보다 낫다.
   *
   * <p>둘 다 적혀 있으면 서로 맞는지 본다. 행을 섞어 붙여 넣다가 번호와 이름이
   * 어긋나는 일이 생기는데, 그대로 올리면 이름만 보고는 알아채지 못한다.
   */
  private String resolveCustomerNumber(Map<String, String> row) {
    String number = str(row, "고객번호");
    String name   = str(row, "고객명");

    if (number != null && !number.isBlank()) {
      Customer c = customerRepo.findByCustomerNumber(number.trim()).orElse(null);
      if (c == null) {
        throw new IllegalArgumentException("고객번호를 찾을 수 없습니다: " + number
            + " — 고객관리에 먼저 등록하거나 번호를 확인하세요.");
      }
      if (name != null && !name.isBlank() && !sameName(name, c.getCustomerName())) {
        throw new IllegalArgumentException(
            "고객번호와 고객명이 서로 다릅니다: " + number + " 는 '" + c.getCustomerName()
            + "' 인데 파일에는 '" + name.trim() + "' 로 적혀 있습니다."
            + " 번호가 맞다면 고객명을 비우고 올리세요.");
      }
      return c.getCustomerNumber();
    }

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("고객번호나 고객명 중 하나는 적어야 합니다.");
    }

    List<Customer> found = customerRepo.findByCustomerName(name.trim());
    if (found.isEmpty()) {
      throw new IllegalArgumentException("고객명을 찾을 수 없습니다: " + name
          + " — 고객관리에 먼저 등록하거나 이름을 확인하세요.");
    }
    if (found.size() > 1) {
      String numbers = found.stream().map(Customer::getCustomerNumber).limit(5)
          .collect(java.util.stream.Collectors.joining(", "));
      throw new IllegalArgumentException("같은 이름의 고객이 " + found.size() + "명입니다: " + name
          + " (" + numbers + ") — 고객번호를 적어 주세요.");
    }
    return found.get(0).getCustomerNumber();
  }

  /**
   * 같은 고객으로 볼 이름인가.
   *
   * <p>딱 맞을 때만 통과시키면 쓸 수 없다. 받아 적은 자료에는 '(주)' 를 떼거나
   * 띄어쓰기가 다르거나 뒤가 잘린 이름이 흔하다('지에스렌트카주식회사' ↔
   * '지에스렌트카주식회'). 고객번호가 이미 적혀 있으면 어느 고객인지는 번호로
   * 정해지므로, 이름 대조는 행을 잘못 붙여 넣은 것을 걸러 내는 그물이면 된다.
   *
   * <p>그래서 띄어쓰기와 법인 표기를 털어 낸 뒤, 같거나 한쪽이 다른 쪽으로 시작하면
   * 같은 이름으로 본다. 아주 다른 이름은 그대로 걸린다.
   */
  private static boolean sameName(String fromFile, String stored) {
    String a = normalizeName(fromFile);
    String b = normalizeName(stored);
    if (a.isEmpty() || b.isEmpty()) return true;      // 비교할 것이 없으면 번호를 믿는다
    return a.equals(b) || a.startsWith(b) || b.startsWith(a);
  }

  private static String normalizeName(String s) {
    if (s == null) return "";
    return s.replace("주식회사", "")
            .replace("(주)", "")
            .replace("㈜", "")
            .replaceAll("\\s+", "")
            .trim();
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
