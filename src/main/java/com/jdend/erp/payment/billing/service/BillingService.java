package com.jdend.erp.payment.billing.service;

import com.jdend.erp.accounting.settings.service.OtherAccountSettingsService;
import com.jdend.erp.contract.entity.Contract;
import com.jdend.erp.contract.repository.ContractRepository;
import com.jdend.erp.customer.Customer;
import com.jdend.erp.customer.CustomerRepository;
import com.jdend.erp.payment.billing.dto.BillingCreateRequest;
import com.jdend.erp.payment.billing.dto.BillingCreateResponse;
import com.jdend.erp.payment.billing.dto.BillingListRowResponse;
import com.jdend.erp.payment.billing.dto.BillingUpdateRequest;
import com.jdend.erp.payment.billing.entity.BillingLines;
import com.jdend.erp.payment.billing.entity.Billings;
import com.jdend.erp.payment.billing.repository.BillingLineRepository;
import com.jdend.erp.payment.billing.repository.BillingRepository;
import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import com.jdend.erp.payment.schedule.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BillingService {

  private final BillingRepository billingRepository;
  private final BillingLineRepository billingLineRepository;
  private final PaymentScheduleRepository paymentSchedulesRepository;
  private final ContractRepository contractRepository;
  private final CustomerRepository customerRepository;
  private final OtherAccountSettingsService accountSettings;

  @Transactional
  public BillingCreateResponse create(BillingCreateRequest req) {
    if (req.getBillingDate() == null) {
      throw new IllegalArgumentException("billingDate is required");
    }
    if (req.getTaxStartDate() == null || req.getTaxEndDate() == null) {
      throw new IllegalArgumentException("taxStartDate/taxEndDate is required");
    }

    LocalDate start = req.getTaxStartDate();
    LocalDate end = req.getTaxEndDate();

    List<PaymentSchedule> schedules;

    if ("individual".equalsIgnoreCase(req.getType())) {
      if (req.getCustomerNumber() == null || req.getCustomerNumber().isBlank()) {
        throw new IllegalArgumentException("customerNumber is required for individual");
      }

      List<String> contractNumbers = contractRepository.findContractNumbersByCustomerNumber(req.getCustomerNumber());
      schedules = contractNumbers.isEmpty()
          ? List.of()
          : paymentSchedulesRepository.findByTaxInvoiceDateBetweenAndContractNumbers(start, end, contractNumbers);
    } else {
      schedules = paymentSchedulesRepository.findByTaxInvoiceDateBetween(start, end);
    }

    if (schedules == null || schedules.isEmpty()) {
      return new BillingCreateResponse("-", 0, 0, 0L);
    }

    schedules = schedules.stream()
        .sorted(Comparator
            .comparing((PaymentSchedule ps) -> nvl(ps.getContractNumber()))
            .thenComparing(PaymentSchedule::getTaxInvoiceDate, Comparator.nullsLast(LocalDate::compareTo))
            .thenComparing(PaymentSchedule::getInstallmentNo, Comparator.nullsLast(Integer::compareTo))
        )
        .collect(Collectors.toList());

    // 계약구분(장기/단기) 필터 적용
    if (req.getContractCategory() != null && !req.getContractCategory().isBlank()) {
      List<String> allowedCns = contractRepository.findContractNumbersByLoanType(req.getContractCategory());
      Set<String> allowedSet = new HashSet<>(allowedCns);
      schedules = schedules.stream()
          .filter(ps -> ps.getContractNumber() != null && allowedSet.contains(ps.getContractNumber()))
          .collect(Collectors.toList());
      if (schedules.isEmpty()) {
        return new BillingCreateResponse("-", 0, 0, 0L);
      }
    }

    int created = 0;
    int skipped = 0;
    long total = 0L;
    String firstBillingNo = null;

    Map<String, List<PaymentSchedule>> grouped = schedules.stream()
        .collect(Collectors.groupingBy(
            this::makeGroupingKey,
            LinkedHashMap::new,
            Collectors.toList()
        ));

    for (Map.Entry<String, List<PaymentSchedule>> entry : grouped.entrySet()) {
      List<PaymentSchedule> groupSchedules = entry.getValue();

      List<PaymentSchedule> eligibleSchedules = new ArrayList<>();
      for (PaymentSchedule ps : groupSchedules) {
        Long scheduleId = ps.getId();
        if (scheduleId == null || billingLineRepository.existsByScheduleId(scheduleId)) {
          skipped++;
          continue;
        }
        eligibleSchedules.add(ps);
      }

      if (eligibleSchedules.isEmpty()) {
        continue;
      }

      PaymentSchedule first = eligibleSchedules.get(0);
      HeaderInfo header = resolveHeaderInfo(first, req);

      Billings billing = new Billings();
      billing.setBillingNo(genBillingNo());
      billing.setBillingType(req.getType());
      billing.setBillingDate(req.getBillingDate());
      billing.setTaxStartDate(req.getTaxStartDate());
      billing.setTaxEndDate(req.getTaxEndDate());
      billing.setOverdueType(req.getOverdueType());
      billing.setStatus("CREATED");

      billing.setContractNumber(blankToNull(header.contractNumber));
      billing.setCustomerId(header.customerId);
      billing.setCustomerNumber(blankToNull(header.customerNumber));
      billing.setCustomerName(blankToNull(header.customerName));
      billing.setEmail(blankToNull(header.email));
      billing.setRentAmount(header.rentAmount);
      billing.setTaxInvoiceDate(header.taxInvoiceDate);
      billing.setPaymentDate(header.paymentDate);
      billing.setTotalAmount(0L);

      billingRepository.save(billing);

      long billingTotal = 0L;

      for (PaymentSchedule ps : eligibleSchedules) {
        BillingLines line = new BillingLines();
        line.setBillingId(billing.getId());
        line.setContractId(ps.getContractId());
        line.setContractNumber(ps.getContractNumber());
        line.setScheduleId(ps.getId());
        line.setInstallmentNo(ps.getInstallmentNo());
        line.setTaxInvoiceDate(ps.getTaxInvoiceDate());
        line.setDueDate(ps.getPaymentDate());
        line.setRentAmount(ps.getRentAmount());

        billingLineRepository.save(line);

        created++;
        long amt = ps.getRentAmount() == null ? 0L : ps.getRentAmount();
        billingTotal += amt;
        total += amt;
      }

      billing.setTotalAmount(billingTotal);
      billingRepository.save(billing);

      if (firstBillingNo == null) {
        firstBillingNo = billing.getBillingNo();
      }
    }

    return new BillingCreateResponse(
        firstBillingNo == null ? "-" : firstBillingNo,
        created,
        skipped,
        total
    );
  }

  public List<BillingListRowResponse> search(LocalDate startDate, LocalDate endDate, String customerName, String contractNumber) {
    return billingRepository.search(startDate, endDate, customerName, contractNumber).stream()
        .map(b -> new BillingListRowResponse(
            b.getBillingNo(),
            b.getContractNumber(),
            b.getCustomerName(),
            b.getEmail(),
            b.getBillingDate(),
            b.getStatus(),
            b.getTotalAmount()
        ))
        .toList();
  }

  public Billings getOne(String billingNo) {
    return billingRepository.findByBillingNo(billingNo)
        .orElseThrow(() -> new IllegalArgumentException("billing not found: " + billingNo));
  }

  @Transactional
  public void update(String billingNo, BillingUpdateRequest req) {
    Billings b = getOne(billingNo);
    b.setCustomerName(req.getCustomerName());
    b.setEmail(req.getEmail());
    b.setTotalAmount(req.getTotalAmount());
    billingRepository.save(b);
  }

  public String buildPrintHtml(Billings b) {
    DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    long total = b.getTotalAmount() == null ? 0 : b.getTotalAmount();

    // BUG-9차-01: 회사정보를 기타계정관리에서 동적 조회 (하드코딩 제거)
    String lessorName    = accountSettings.getCompanyName();
    String lessorAddress = accountSettings.getCompanyAddress();
    if (lessorName    == null || lessorName.isBlank())    lessorName    = "(회사정보 미설정)";
    if (lessorAddress == null || lessorAddress.isBlank()) lessorAddress = "(회사정보 미설정)";

    List<BillingLines> lines = billingLineRepository.findByBillingIdOrderByInstallmentNoAsc(b.getId());

    String customerName = safe(b.getCustomerName());
    String phone = "-";
    String registrationNumber = "-";
    String address = "-";

    String contractCategory = "장기";
    String contractType = "일반";
    String contractStartDate = "-";
    String contractEndDate = "-";

    // 청구내용은 원금·이자로 나눠 보여준다. 대부업 이자수익은 부가가치세 면세라
    // 공급가액/세액 칸은 두지 않는다.
    long billedPrincipal = 0L;
    long billedInterest = 0L;
    List<Long> scheduleIds = lines.stream()
        .map(BillingLines::getScheduleId)
        .filter(Objects::nonNull)
        .toList();
    if (!scheduleIds.isEmpty()) {
      for (PaymentSchedule ps : paymentSchedulesRepository.findAllById(scheduleIds)) {
        billedPrincipal += ps.getPrincipalAmount() == null ? 0L : ps.getPrincipalAmount();
        billedInterest  += ps.getInterestAmount()  == null ? 0L : ps.getInterestAmount();
      }
    }
    // 스케줄을 못 찾으면(수기 청구 등) 원금·이자를 나눌 근거가 없으므로 총액을 원금 칸에 넣는다.
    // 합계는 어느 쪽이든 맞는다.
    if (billedPrincipal + billedInterest == 0L) {
      billedPrincipal = total;
    }
    String memo = safe(b.getMemo()).equals("-") ? "" : b.getMemo();

    String baseContractNumber = null;
    if (!lines.isEmpty() && lines.get(0).getContractNumber() != null && !lines.get(0).getContractNumber().isBlank()) {
      baseContractNumber = lines.get(0).getContractNumber();
    } else if (b.getContractNumber() != null && !b.getContractNumber().isBlank()) {
      baseContractNumber = b.getContractNumber();
    }

    if (baseContractNumber != null) {
      Optional<Contract> contractOpt = contractRepository.findWithCustomerByContractNumber(baseContractNumber);

      if (contractOpt.isPresent()) {
        Contract contract = contractOpt.get();

        contractCategory = safe(defaultIfBlank(contract.getLoanType(), "신용대출"));
        contractType = safe(defaultIfBlank(contract.getRepaymentMethod(), "원리금균등"));
        contractStartDate = contract.getStartDate() == null ? "-" : contract.getStartDate().format(f);
        contractEndDate = contract.getEndDate() == null ? "-" : contract.getEndDate().format(f);

        Customer customer = null;
        if (contract.getCustomer() != null) {
          customer = contract.getCustomer();
        } else if (contract.getCustomerNumber() != null && !contract.getCustomerNumber().isBlank()) {
          customer = customerRepository.findByCustomerNumber(contract.getCustomerNumber()).orElse(null);
        }

        if (customer != null) {
          customerName = safe(customer.getCustomerName());
          registrationNumber = safe(customer.getRegistrationNumber());
          // Bug2: 법인은 phone 컬럼에 법인등록번호가 저장되므로 연락처는 managerPhone 사용
          if ("법인".equals(customer.getCustomerType())) {
            phone = safe(customer.getManagerPhone());
          } else {
            phone = safe(firstNonBlank(customer.getPhone(), customer.getManagerPhone()));
          }
          // Bug3: billAddress에 업태가 잘못 입력된 경우가 있으므로 address를 우선 사용
          address = safe(firstNonBlank(customer.getAddress(), customer.getBillAddress()));
        }
      }
    }

    return """
      <!doctype html>
      <html lang="ko">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>청구서 출력</title>
        <style>
          *{box-sizing:border-box;}
          html,body{margin:0;padding:0;background:#fff;font-family:'Malgun Gothic','맑은 고딕',Arial,sans-serif;color:#111;}
          .page{width:210mm;min-height:297mm;margin:0 auto;padding:12mm 10mm;background:#fff;}
          table{width:100%%;border-collapse:collapse;table-layout:fixed;}
          .sheet{border:1px solid #222;}
          .sheet td,.sheet th{border:1px solid #222;padding:6px 8px;font-size:13px;vertical-align:middle;word-break:break-all;height:34px;}
          .title-row td{font-size:22px;font-weight:700;text-align:center;height:54px;}
          .section-title{text-align:center;font-size:15px !important;font-weight:700;height:42px !important;background:#fff;}
          .label{text-align:center;font-weight:400;background:#fff;}
          .value{text-align:left;background:#fff;}
          .center{text-align:center;}
          .right{text-align:right;}
          .big-row td{height:60px;}
          .note-row td{height:110px;vertical-align:top;}
          .footer-row td{height:46px;}
          .lessor-block{line-height:1.8;text-align:center;font-size:14px;}
          @media print{
            .page{width:auto;min-height:auto;margin:0;padding:0;}
          }
        </style>
      </head>
      <body>
        <div class="page">
          <table class="sheet">
            <colgroup>
              <col style="width:11.11%%"><col style="width:11.11%%"><col style="width:11.11%%">
              <col style="width:11.11%%"><col style="width:11.11%%"><col style="width:11.11%%">
              <col style="width:11.11%%"><col style="width:11.11%%"><col style="width:11.11%%">
            </colgroup>

            <tr class="title-row">
              <td colspan="9">청 구 서</td>
            </tr>

            <tr>
              <td colspan="9" class="section-title">수 신 : %s 귀 중</td>
            </tr>

            <tr>
              <td class="label">사업자번호</td>
              <td colspan="4" class="value">%s</td>
              <td class="label">고객연락처</td>
              <td colspan="3" class="value">%s</td>
            </tr>

            <tr>
              <td class="label">주소</td>
              <td colspan="8" class="value">%s</td>
            </tr>

            <tr class="big-row">
              <td colspan="9" class="section-title">채권내용</td>
            </tr>

            <tr>
              <td colspan="3" class="label">대출구분</td>
              <td colspan="2" class="label">상환방식</td>
              <td colspan="2" class="label">계약시작일</td>
              <td colspan="2" class="label">계약종료일</td>
            </tr>

            <tr>
              <td colspan="3" class="value center">%s</td>
              <td colspan="2" class="value center">%s</td>
              <td colspan="2" class="value center">%s</td>
              <td colspan="2" class="value center">%s</td>
            </tr>

            <tr class="big-row">
              <td colspan="9" class="section-title">청구내용</td>
            </tr>

            <tr>
              <td colspan="3" class="label">청구원금</td>
              <td colspan="2" class="value right">%,d</td>
              <td colspan="2" class="label">청구이자</td>
              <td colspan="2" class="value right">%,d</td>
            </tr>

            <tr>
              <td colspan="3" class="label">청구합계</td>
              <td colspan="6" class="value right">%,d</td>
            </tr>

            <tr>
              <td colspan="9" class="value" style="font-size:11px;color:#555;">
                대부업 이자수익은 부가가치세 면세 대상이므로 세금계산서를 발행하지 않습니다.
              </td>
            </tr>

            <tr class="note-row">
              <td colspan="9" class="value"><span style="font-weight:700;">*비고</span><br>%s</td>
            </tr>

            <tr style="height:140px;">
              <td colspan="9">&nbsp;</td>
            </tr>

            <tr>
              <td colspan="9" class="section-title">대부업자</td>
            </tr>

            <tr class="footer-row">
              <td colspan="7" class="lessor-block">%s</td>
              <td colspan="2" class="center">(서명 또는 인)</td>
            </tr>

            <tr class="footer-row">
              <td colspan="9" class="lessor-block">%s</td>
            </tr>
          </table>
        </div>
        <script>window.onload=function(){setTimeout(function(){window.print();},300);};</script>
      </body>
      </html>
      """.formatted(
        safe(customerName),
        safe(registrationNumber),
        safe(phone),
        safe(address),
        safe(contractCategory),
        safe(contractType),
        safe(contractStartDate),
        safe(contractEndDate),
        billedPrincipal,
        billedInterest,
        billedPrincipal + billedInterest,
        safe(memo).replace("\n", "<br>"),
        lessorName,
        lessorAddress
      );
  }

  @Transactional
  public void send(String billingNo, String email) {
    Billings b = getOne(billingNo);
    b.setEmail(email);
    b.setStatus("SENT");
    billingRepository.save(b);
  }

  private HeaderInfo resolveHeaderInfo(PaymentSchedule ps, BillingCreateRequest req) {
    HeaderInfo info = new HeaderInfo();

    info.contractNumber = ps.getContractNumber();
    info.taxInvoiceDate = ps.getTaxInvoiceDate();
    info.paymentDate = ps.getPaymentDate();
    info.rentAmount = ps.getRentAmount();

    if (ps.getContractNumber() != null && !ps.getContractNumber().isBlank()) {
      Optional<Contract> contractOpt = contractRepository.findWithCustomerByContractNumber(ps.getContractNumber());

      if (contractOpt.isPresent()) {
        Contract contract = contractOpt.get();

        info.contractNumber = contract.getContractNumber();
        info.customerNumber = contract.getCustomerNumber();

        if (contract.getCustomer() != null) {
          Customer c = contract.getCustomer();
          info.customerId = c.getId();
          info.customerName = c.getCustomerName();
          info.email = firstNonBlank(c.getBillEmail(), c.getManagerEmail());
        } else if (contract.getCustomerNumber() != null && !contract.getCustomerNumber().isBlank()) {
          Optional<Customer> customerOpt = customerRepository.findByCustomerNumber(contract.getCustomerNumber());
          if (customerOpt.isPresent()) {
            Customer c = customerOpt.get();
            info.customerId = c.getId();
            info.customerName = c.getCustomerName();
            info.email = firstNonBlank(c.getBillEmail(), c.getManagerEmail());
          }
        }
      }
    }

    if ((info.customerNumber == null || info.customerNumber.isBlank())
        && req.getCustomerNumber() != null
        && !req.getCustomerNumber().isBlank()) {
      info.customerNumber = req.getCustomerNumber();

      Optional<Customer> customerOpt = customerRepository.findByCustomerNumber(req.getCustomerNumber());
      if (customerOpt.isPresent()) {
        Customer c = customerOpt.get();
        if (info.customerId == null) info.customerId = c.getId();
        if (info.customerName == null || info.customerName.isBlank()) info.customerName = c.getCustomerName();
        if (info.email == null || info.email.isBlank()) info.email = firstNonBlank(c.getBillEmail(), c.getManagerEmail());
      }
    }

    return info;
  }

  private String makeGroupingKey(PaymentSchedule ps) {
    if (ps.getContractNumber() != null && !ps.getContractNumber().isBlank()) {
      return "CONTRACT:" + ps.getContractNumber().trim();
    }
    if (ps.getContractId() != null) {
      return "CONTRACT_ID:" + ps.getContractId();
    }
    if (ps.getId() != null) {
      return "SCHEDULE:" + ps.getId();
    }
    return "ETC:" + UUID.randomUUID();
  }

  private String genBillingNo() {
    return "B" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  private String nvl(String s) {
    return s == null ? "" : s;
  }

  private String safe(String s) {
    return s == null || s.isBlank() ? "-" : s;
  }

  private String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s;
  }

  private String defaultIfBlank(String value, String defaultValue) {
    return (value == null || value.isBlank()) ? defaultValue : value;
  }

  private String firstNonBlank(String... values) {
    if (values == null) return null;
    for (String v : values) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  private static class HeaderInfo {
    String contractNumber;
    Long customerId;
    String customerNumber;
    String customerName;
    String email;
    Long rentAmount;
    LocalDate taxInvoiceDate;
    LocalDate paymentDate;
  }
}