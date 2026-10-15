package com.jdend.erp.payment.payment.service;

import com.jdend.erp.common.excel.ExcelReader;
import com.jdend.erp.common.excel.ExcelTemplateWriter;
import com.jdend.erp.common.excel.ExcelUploadResultResponse;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.payment.payment.dto.PaymentUpsertRequest;
import com.jdend.erp.payment.payment.entity.Payment;
import com.jdend.erp.payment.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static com.jdend.erp.common.excel.ExcelRowParsers.dateVal;
import static com.jdend.erp.common.excel.ExcelRowParsers.longVal;
import static com.jdend.erp.common.excel.ExcelRowParsers.str;

/**
 * 상환내역 엑셀 일괄 업로드 — 지난 내역을 대출채권별로 옮겨 적는다.
 *
 * <p>수납 업로드와 다른 점은 <b>원금과 이자를 파일에 적힌 그대로 쓴다</b>는 것이다.
 * 지난 상환의 원금·이자 나눔은 이미 끝난 일이고 전표도 그 금액으로 끊겨 있다.
 * 여기서 변제충당을 다시 계산하면 올린 숫자와 ERP 의 숫자가 달라진다.
 *
 * <p>들어가는 곳은 보통의 수납(payments)과 같은 자리다. 따로 된 표를 만들면
 * 수납현황·상환스케줄·미회수원금이 두 군데를 더해야 맞게 되고, 앞으로 ERP 로
 * 받는 수납과 섞어 보기가 어려워진다. 같은 자리에 넣으면 올린 내역과 앞으로의
 * 수납이 한 줄로 이어진다.
 *
 * <p>전표는 만들지 않는다. 그 시기 전표는 전표 일괄업로드로 따로 올리기 때문에,
 * 여기서 또 만들면 같은 거래가 두 번 적힌다.
 */
@Service
@RequiredArgsConstructor
public class RepaymentHistoryUploadService {

  private static final List<String> HEADERS = List.of(
      "계약번호", "상환일자", "원금", "이자", "상환후잔액", "비고"
  );

  private static final List<String> SAMPLE_ROW = List.of(
      "CRD260101001001", "20230821", "1990410", "17260", "8009590",
      "샘플 행입니다. 지우고 실제 데이터를 넣으세요. 날짜는 8자리 숫자(20230821)로 적습니다."
          + " 상환후잔액은 비워도 되며, 적으면 ERP 계산값과 맞는지 대조합니다."
  );

  private final PaymentService paymentService;
  private final PaymentRepository paymentRepo;
  private final ContractRepository contractRepo;

  public byte[] template() {
    return ExcelTemplateWriter.write(HEADERS, SAMPLE_ROW);
  }

  /**
   * @param dryRun true 면 저장하지 않고 검사만 한다.
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

    // 계약번호 → 이 파일에서 지금까지 읽은 원금 누계.
    //
    // 검사만 할 때(dryRun)는 아무것도 저장되지 않으므로 앞선 행의 몫을 여기에 쌓아
    // 둬야 '대출금을 넘는지'를 제대로 본다. 반대로 실제로 저장할 때는 앞선 행이 이미
    // DB 에 들어가 있어서, 여기에도 쌓으면 같은 금액을 두 번 세게 된다.
    Map<String, Long> principalInFile = new HashMap<>();

    // 파일 안에 똑같은 줄이 두 번 있는 경우. 검사만 할 때는 DB 를 봐도 알 수 없다.
    Set<String> seenInFile = new HashSet<>();

    for (int i = 0; i < rows.size(); i++) {
      int rowNumber = i + 2;                       // 1행은 머리글
      Map<String, String> row = rows.get(i);
      try {
        Parsed p = parse(row, principalInFile, seenInFile, dryRun);
        if (!dryRun) {
          paymentService.create(toRequest(p));
        } else {
          // 저장하지 않으므로 앞선 행의 몫을 여기에 쌓아 둔다
          principalInFile.merge(p.contract().getContractNumber(), p.principal(), Long::sum);
        }
        seenInFile.add(keyOf(p));
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

  // ── 한 행 읽기 · 검사 ──────────────────────────────────────

  private record Parsed(Contract contract, LocalDate date,
                        long principal, long interest, Long balance, String memo) { }

  private static String keyOf(Parsed p) {
    return p.contract().getContractNumber() + "|" + p.date() + "|" + p.principal() + "|" + p.interest();
  }

  private Parsed parse(Map<String, String> row, Map<String, Long> principalInFile,
                       Set<String> seenInFile, boolean dryRun) {
    String contractNumber = str(row, "계약번호");
    if (contractNumber == null || contractNumber.isBlank()) {
      throw new IllegalArgumentException("계약번호가 비어 있습니다.");
    }
    Contract c = contractRepo.findByContractNumber(contractNumber.trim())
        .orElseThrow(() -> new IllegalArgumentException(
            "계약번호를 찾을 수 없습니다: " + contractNumber
            + " — 대출을 먼저 등록하거나 번호를 확인하세요."));

    LocalDate date = dateVal(row, "상환일자");
    if (date == null) {
      throw new IllegalArgumentException("상환일자가 비어 있거나 날짜가 아닙니다. 8자리 숫자로 적으세요. 예: 20230821");
    }

    long principal = nz(longVal(row, "원금"));
    long interest  = nz(longVal(row, "이자"));
    if (principal < 0 || interest < 0) {
      throw new IllegalArgumentException("원금과 이자는 0 이상이어야 합니다.");
    }
    if (principal + interest <= 0) {
      throw new IllegalArgumentException("원금과 이자가 모두 비어 있습니다. 한쪽이라도 금액이 있어야 합니다.");
    }

    // 이미 올린 같은 건인지 — 계약번호·일자·금액이 모두 같으면 같은 건으로 본다.
    // 파일을 두 번 올려 상환내역이 두 배가 되는 일을 막는다.
    long amount = principal + interest;
    boolean already = paymentRepo.findByContractNumberOrderByPaymentDateAscIdAsc(c.getContractNumber())
        .stream()
        .anyMatch(p -> date.equals(p.getPaymentDate())
                    && amount == nz(p.getPaymentAmount())
                    && p.hasFixedAllocation()
                    && principal == nz(p.getFixedPrincipal())
                    && interest == nz(p.getFixedInterest()));
    if (already) {
      throw new IllegalArgumentException(
          "이미 올린 상환내역입니다: " + date + " " + String.format("%,d", amount) + "원"
          + " — 같은 파일을 다시 올린 것 같습니다.");
    }
    String key = c.getContractNumber() + "|" + date + "|" + principal + "|" + interest;
    if (seenInFile.contains(key)) {
      throw new IllegalArgumentException(
          "이 파일 안에서 똑같은 줄이 두 번 나옵니다: " + date + " "
          + String.format("%,d", amount) + "원");
    }

    // 원금은 대출금을 넘을 수 없다. 넘으면 미회수원금이 음수가 된다.
    long alreadyPrincipal = paidPrincipalOf(c.getContractNumber())
                          + (dryRun ? principalInFile.getOrDefault(c.getContractNumber(), 0L) : 0L);
    long loanAmount = nz(c.getLoanAmount());
    if (alreadyPrincipal + principal > loanAmount) {
      throw new IllegalArgumentException(
          "원금 합계가 대출금을 넘습니다. 대출금 " + String.format("%,d", loanAmount)
          + "원, 이미 상환 " + String.format("%,d", alreadyPrincipal)
          + "원, 이 행 " + String.format("%,d", principal) + "원");
    }

    // 상환후잔액이 적혀 있으면 대조한다. 틀린 줄을 그냥 넣으면 나중에
    // 미회수원금이 왜 다른지 찾기 어려워진다.
    Long balance = longVal(row, "상환후잔액");
    if (balance != null && balance >= 0) {
      long expected = loanAmount - (alreadyPrincipal + principal);
      if (expected != balance) {
        throw new IllegalArgumentException(
            "상환후잔액이 맞지 않습니다. 파일 " + String.format("%,d", balance)
            + "원, 계산값 " + String.format("%,d", expected) + "원"
            + " (대출금 " + String.format("%,d", loanAmount)
            + " - 상환원금 누계 " + String.format("%,d", alreadyPrincipal + principal) + ")");
      }
    }

    return new Parsed(c, date, principal, interest, balance, str(row, "비고"));
  }

  /** 이 채권에 이미 올라가 있는 상환 원금 누계 */
  private long paidPrincipalOf(String contractNumber) {
    return paymentRepo.findByContractNumberOrderByPaymentDateAscIdAsc(contractNumber).stream()
        .mapToLong(p -> p.hasFixedAllocation() ? nz(p.getFixedPrincipal()) : 0L)
        .sum();
  }

  private PaymentUpsertRequest toRequest(Parsed p) {
    return PaymentUpsertRequest.builder()
        .contractNumber(p.contract().getContractNumber())
        .paymentDate(p.date())
        .paymentAmount(p.principal() + p.interest())
        .applyDate(p.date())
        .fixedPrincipal(p.principal())
        .fixedInterest(p.interest())
        .fixedBalance(p.balance())
        .memo(p.memo())
        .surplusPolicy("원금충당")
        .paymentSource("보통예금")
        .createVoucher(false)          // 그 시기 전표는 전표 일괄업로드로 따로 올린다
        .build();
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
