package com.jdend.erp.accounting.statements.service;

import com.jdend.erp.accounting.statements.dto.*;
import com.jdend.erp.accounting.statements.repository.StatementAggRepository;
import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import com.jdend.erp.management.financial.entity.FinancialStatementAccount;
import com.jdend.erp.management.financial.repository.FinancialStatementAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatementService {

  private final StatementAggRepository aggRepo;
  private final FinancialStatementAccountRepository accountRepo;

  private static final Set<String> CREDIT_NORMAL_CATEGORIES = Set.of("LIABILITY", "EQUITY", "REVENUE");

  private static final Map<String, String> CATEGORY_LABEL = Map.of(
      "ASSET", "자산",
      "LIABILITY", "부채",
      "EQUITY", "자본",
      "REVENUE", "수익",
      "EXPENSE", "비용"
  );

  // ==========================
  // 재무상태표
  // ==========================
  @Transactional(readOnly = true)
  public BalanceSheetResponse balance(LocalDate ref, String status) {
    if (ref == null) throw new IllegalArgumentException("referenceDate는 필수입니다.");

    // ① 기준일까지의 누적 전표 — 자산/부채/자본 B/S 계정 집계에 사용
    List<StatementAggRepository.LineSumRow> rows = aggRepo.sumByAccountToDate(ref, status);
    Map<String, Long> debit = new HashMap<>();
    Map<String, Long> credit = new HashMap<>();
    splitByLineType(rows, debit, credit);

    // ② 당해 회계연도(1월 1일 ~ 기준일) 전표 — 당기순이익 계산에 사용
    LocalDate currentYearStart = ref.withMonth(1).withDayOfMonth(1);
    List<StatementAggRepository.LineSumRow> cyRows = aggRepo.sumByAccountBetween(currentYearStart, ref, status);
    Map<String, Long> cyDebit = new HashMap<>();
    Map<String, Long> cyCredit = new HashMap<>();
    splitByLineType(cyRows, cyDebit, cyCredit);

    List<FinancialStatementAccount> all = accountRepo.findAll().stream()
        .filter(a -> "사용".equals(a.getIsActive()))
        .collect(Collectors.toList());
    Map<Long, List<FinancialStatementAccount>> byParent = groupByParent(all);

    StatementNodeResponse asset    = buildRootNode(all, byParent, "ASSET",     debit,   credit);
    StatementNodeResponse liability = buildRootNode(all, byParent, "LIABILITY", debit,   credit);
    StatementNodeResponse equity   = buildRootNode(all, byParent, "EQUITY",    debit,   credit);

    // BUG-⑪ 수정: buildRootNode 중복 호출 제거 — 결과를 변수에 저장해 재사용한다.
    // 마감분개(전표) 없이 계산 시점에 수익-비용을 자본에 가산해 자산=부채+자본 균형을 맞춘다.
    // 전년도까지의 누적 순이익 → 이익잉여금(전기이월)
    // 당해연도 순이익                → 당기순이익
    // 두 항목의 합계가 전체 (수익-비용) 누적과 같으므로 균형은 유지된다.
    StatementNodeResponse revenueNode   = buildRootNode(all, byParent, "REVENUE", debit,   credit);
    StatementNodeResponse expenseNode   = buildRootNode(all, byParent, "EXPENSE", debit,   credit);
    StatementNodeResponse cyRevenueNode = buildRootNode(all, byParent, "REVENUE", cyDebit, cyCredit);
    StatementNodeResponse cyExpenseNode = buildRootNode(all, byParent, "EXPENSE", cyDebit, cyCredit);

    long totalNetIncome   = revenueNode.getAmount()   - expenseNode.getAmount();
    long currentNetIncome = cyRevenueNode.getAmount() - cyExpenseNode.getAmount();

    long retainedEarnings = totalNetIncome - currentNetIncome; // 전년도까지 누적 순이익

    java.util.List<StatementNodeResponse> equityChildren = new java.util.ArrayList<>(
        equity.getChildren() != null ? equity.getChildren() : java.util.List.of());

    // 전년도 이익이 있을 때만 이익잉여금(전기이월) 항목을 표시한다
    if (retainedEarnings != 0) {
      equityChildren.add(StatementNodeResponse.builder()
          .accountCode(null)
          .accountName("이익잉여금 (전기이월)")
          .level(2)
          .amount(retainedEarnings)
          .children(java.util.List.of())
          .build());
    }
    equityChildren.add(StatementNodeResponse.builder()
        .accountCode(null)
        .accountName("당기순이익")
        .level(2)
        .amount(currentNetIncome)
        .children(java.util.List.of())
        .build());

    StatementNodeResponse equityWithNetIncome = StatementNodeResponse.builder()
        .accountCode(equity.getAccountCode())
        .accountName(equity.getAccountName())
        .level(equity.getLevel())
        .amount(equity.getAmount() + totalNetIncome)
        .children(equityChildren)
        .build();

    return BalanceSheetResponse.builder()
        .asset(asset)
        .liability(liability)
        .equity(equityWithNetIncome)
        .totalAsset(asset.getAmount())
        .totalLiability(liability.getAmount())
        .totalEquity(equityWithNetIncome.getAmount())
        .build();
  }

  // ==========================
  // 손익계산서
  // ==========================
  @Transactional(readOnly = true)
  public IncomeStatementResponse income(LocalDate start, LocalDate end, String status) {
    if (start == null || end == null) throw new IllegalArgumentException("startDate/endDate는 필수입니다.");
    if (start.isAfter(end)) throw new IllegalArgumentException("startDate는 endDate보다 이후일 수 없습니다.");

    List<StatementAggRepository.LineSumRow> rows = aggRepo.sumByAccountBetween(start, end, status);
    Map<String, Long> debit = new HashMap<>();
    Map<String, Long> credit = new HashMap<>();
    splitByLineType(rows, debit, credit);

    List<FinancialStatementAccount> all = accountRepo.findAll().stream()
        .filter(a -> "사용".equals(a.getIsActive()))
        .collect(Collectors.toList());
    Map<Long, List<FinancialStatementAccount>> byParent = groupByParent(all);

    StatementNodeResponse revenue = buildRootNode(all, byParent, "REVENUE", debit, credit);
    StatementNodeResponse expense = buildRootNode(all, byParent, "EXPENSE", debit, credit);

    // 영업/영업외 구분 — 중분류 계정코드 기준
    long operatingRevenue    = sumChildren(revenue, CODE_OPERATING_REVENUE);
    long nonOperatingRevenue = sumChildren(revenue, CODE_NON_OPERATING_REVENUE);
    long operatingExpense    = sumChildren(expense, CODE_OPERATING_COST, CODE_SGA);
    long nonOperatingExpense = sumChildren(expense, CODE_NON_OPERATING_EXPENSE);
    long incomeTax           = sumChildren(expense, CODE_INCOME_TAX);

    long operatingIncome = operatingRevenue - operatingExpense;

    return IncomeStatementResponse.builder()
        .revenue(revenue)
        .expense(expense)
        .totalRevenue(revenue.getAmount())
        .totalExpense(expense.getAmount())
        .operatingRevenue(operatingRevenue)
        .operatingExpense(operatingExpense)
        .operatingIncome(operatingIncome)
        .nonOperatingRevenue(nonOperatingRevenue)
        .nonOperatingExpense(nonOperatingExpense)
        .incomeTax(incomeTax)
        .netIncome(revenue.getAmount() - expense.getAmount())
        .build();
  }

  // 손익계산서 중분류 계정코드
  private static final String CODE_OPERATING_REVENUE     = "4001"; // 영업수익
  private static final String CODE_NON_OPERATING_REVENUE = "4002"; // 영업외수익
  private static final String CODE_OPERATING_COST         = "5001"; // 영업비용 (이자비용 등)
  private static final String CODE_SGA                   = "5002"; // 판매비와관리비
  private static final String CODE_NON_OPERATING_EXPENSE = "5003"; // 영업외비용
  private static final String CODE_INCOME_TAX            = "5004"; // 법인세

  /** 대분류 노드의 직계 자식 중 지정한 계정코드들의 금액 합계 */
  private static long sumChildren(StatementNodeResponse root, String... accountCodes) {
    if (root == null || root.getChildren() == null) return 0L;
    Set<String> targets = Set.of(accountCodes);
    return root.getChildren().stream()
        .filter(c -> c.getAccountCode() != null && targets.contains(c.getAccountCode()))
        .mapToLong(c -> c.getAmount() == null ? 0L : c.getAmount())
        .sum();
  }

  // ==========================
  // 계정 상세내역 (코드 prefix로 하위 leaf 계정명 전체를 모아 조회)
  // ==========================
  @Transactional(readOnly = true)
  public List<BalanceDetailRowResponse> balanceDetails(String accountCode, LocalDate startDate, LocalDate referenceDate, String status) {
    if (referenceDate == null) {
      throw new IllegalArgumentException("referenceDate는 필수입니다.");
    }
    if (accountCode == null || accountCode.isBlank()) {
      throw new IllegalArgumentException("accountCode는 필수입니다.");
    }

    List<String> accountCodes = accountRepo.findAll().stream()
        .filter(a -> "사용".equals(a.getIsActive()))
        .filter(a -> a.getAccountCode().startsWith(accountCode))
        .map(FinancialStatementAccount::getAccountCode)
        .toList();

    if (accountCodes.isEmpty()) {
      return List.of();
    }

    return aggRepo.findBalanceDetails(startDate, referenceDate, accountCodes, status);
  }

  // ==========================
  // helpers
  // ==========================
  private static void splitByLineType(
      List<StatementAggRepository.LineSumRow> rows,
      Map<String, Long> debit,
      Map<String, Long> credit
  ) {
    for (var r : rows) {
      String code = safe(r.getAccountCode());
      if (code.isEmpty()) continue; // 코드 없는 라인은 집계 제외
      long amt = r.getAmt() == null ? 0L : r.getAmt();
      if ("DEBIT".equalsIgnoreCase(r.getLineType())) debit.merge(code, amt, Long::sum);
      else credit.merge(code, amt, Long::sum);
    }
  }

  private static Map<Long, List<FinancialStatementAccount>> groupByParent(List<FinancialStatementAccount> all) {
    return all.stream()
        .filter(a -> a.getParentId() != null)
        .collect(Collectors.groupingBy(FinancialStatementAccount::getParentId));
  }

  private StatementNodeResponse buildRootNode(
      List<FinancialStatementAccount> all,
      Map<Long, List<FinancialStatementAccount>> byParent,
      String category,
      Map<String, Long> debit,
      Map<String, Long> credit
  ) {
    Optional<FinancialStatementAccount> root = all.stream()
        .filter(a -> a.getParentId() == null && category.equals(a.getCategory()))
        .findFirst();

    // 신규 회사(테넌트) DB는 재무제표관리에서 계정을 등록하기 전까지 대분류 자체가 없을 수 있다.
    // 이 경우 에러 대신 금액 0인 빈 대분류 노드를 내려준다(재무제표관리에서 계정을 등록하면 채워짐).
    if (root.isEmpty()) {
      return StatementNodeResponse.builder()
          .accountCode(null)
          .accountName(CATEGORY_LABEL.get(category))
          .level(1)
          .amount(0L)
          .children(List.of())
          .build();
    }

    return buildNode(root.get(), byParent, debit, credit, new java.util.HashSet<>());
  }

  private StatementNodeResponse buildNode(
      FinancialStatementAccount node,
      Map<Long, List<FinancialStatementAccount>> byParent,
      Map<String, Long> debit,
      Map<String, Long> credit,
      java.util.Set<Long> visiting
  ) {
    // parentId가 순환을 이루면 무한 재귀로 서버가 죽으므로, 방문 중인 노드를 추적해 끊는다.
    if (!visiting.add(node.getId())) {
      throw new IllegalStateException("계정 트리에 순환 참조가 있습니다. id=" + node.getId());
    }

    List<FinancialStatementAccount> childEntities = byParent.getOrDefault(node.getId(), List.of());
    List<StatementNodeResponse> children = childEntities.stream()
        .map(c -> buildNode(c, byParent, debit, credit, visiting))
        .toList();

    visiting.remove(node.getId());

    // 자기 앞으로 달린 전표 금액은 자식이 있든 없든 그대로 더한다.
    //
    // 전에는 자식이 있으면 자기 금액을 버렸다. 집계 계정에 중복으로 더해질까 봐
    // 그랬지만, 대손충당금처럼 차감 계정을 자식으로 두는 계정은 그 자신이
    // 전표를 받는 계정이다. 그래서 장기대여금(100302)에 5,000,000 을 올려도
    // 자식(장기대여금 대손충당금)이 있다는 이유로 0 으로 나왔다.
    //
    // 1003 대출채권 같은 순수 집계 계정은 전표를 받지 않아(기표 '미사용')
    // 여기서 가져올 금액 자체가 0 이므로 중복으로 더해지지 않는다.
    long ownAmount = signedNetAmount(node.getCategory(), node.getAccountCode(), debit, credit);
    long childrenSum = children.stream().mapToLong(StatementNodeResponse::getAmount).sum();

    return StatementNodeResponse.builder()
        .accountCode(node.getAccountCode())
        .accountName(node.getAccountName())
        .level(node.getLevel())
        .amount(ownAmount + childrenSum)
        .children(children)
        .build();
  }

  // ASSET/EXPENSE는 차변 정상잔액(debit-credit), LIABILITY/EQUITY/REVENUE는 대변 정상잔액(credit-debit)
  private long signedNetAmount(String category, String accountCode, Map<String, Long> debit, Map<String, Long> credit) {
    String code = safe(accountCode);
    long d = debit.getOrDefault(code, 0L);
    long c = credit.getOrDefault(code, 0L);
    long net = d - c;
    return CREDIT_NORMAL_CATEGORIES.contains(category) ? -net : net;
  }

  // BUG-11: 계정명 중간 공백까지 정규화 — Map 키 생성/조회 양쪽에서 동일하게 적용되므로
  //          연속 공백 불일치로 인한 집계 누락을 방지한다.
  private static String safe(String s) {
    if (s == null) return "";
    return s.trim().replaceAll("\\s+", " ");
  }

  /**
   * 재무제표에 안 잡히는 전표 라인을 찾아 이유와 함께 돌려준다.
   *
   * 전표를 제대로 쳤는데 재무제표에서 금액이 비면 어디서 샜는지 알 길이 없었다.
   * 집계는 계정코드로만 하고, 계정 트리에서 '사용'인 계정만 타고 내려가므로
   * 다음 경우는 전표가 승인돼 있어도 조용히 빠진다.
   *
   *   1. 계정코드가 비어 있다 — 코드 없이 만들어진 전표
   *   2. 계정코드가 재무제표관리에 없다 — 코드를 바꾸거나 지운 경우
   *   3. 그 계정이나 상위 계정이 '미사용'이다 — 트리에서 끊겨 합산되지 않는다
   */
  @Transactional(readOnly = true)
  public List<UnreflectedLineResponse> unreflected(LocalDate start, LocalDate end, String status) {
    if (start == null || end == null) {
      throw new IllegalArgumentException("startDate, endDate 는 필수입니다.");
    }

    Map<String, FinancialStatementAccount> byCode = accountRepo.findAll().stream()
        .filter(a -> a.getAccountCode() != null)
        .collect(Collectors.toMap(FinancialStatementAccount::getAccountCode, a -> a, (x, y) -> x));
    Map<Long, FinancialStatementAccount> byId = byCode.values().stream()
        .collect(Collectors.toMap(FinancialStatementAccount::getId, a -> a, (x, y) -> x));

    List<UnreflectedLineResponse> out = new ArrayList<>();
    for (VoucherLine l : aggRepo.findLinesBetween(start, end, status)) {
      String reason = reasonNotReflected(l.getAccountCode(), byCode, byId);
      if (reason == null) continue;

      out.add(UnreflectedLineResponse.builder()
          .voucherId(l.getVoucher().getId())
          .voucherNo(l.getVoucher().getVoucherNo())
          .voucherDate(l.getVoucher().getVoucherDate())
          .status(l.getVoucher().getStatus())
          .lineType(l.getLineType())
          .accountCode(l.getAccountCode())
          .accountName(l.getAccountName())
          .amount(l.getAmount())
          .description(l.getDescription())
          .reason(reason)
          .build());
    }
    return out;
  }

  /** 반영되면 null, 안 되면 그 이유 */
  private String reasonNotReflected(String code,
                                    Map<String, FinancialStatementAccount> byCode,
                                    Map<Long, FinancialStatementAccount> byId) {
    if (code == null || code.isBlank()) {
      return "계정코드가 비어 있습니다. 전표를 열어 계정을 다시 고르고 저장하세요.";
    }
    FinancialStatementAccount a = byCode.get(code.trim());
    if (a == null) {
      return "계정코드 " + code + " 가 재무제표관리에 없습니다. 삭제됐거나 코드가 바뀐 계정입니다.";
    }
    if (!"사용".equals(a.getIsActive())) {
      return "계정 " + code + " " + a.getAccountName() + " 이(가) '미사용' 입니다."
           + " 재무제표관리에서 '사용'으로 바꾸면 반영됩니다.";
    }
    // 상위로 올라가며 끊긴 곳이 있는지 — 중간이 미사용이면 트리에서 통째로 빠진다
    FinancialStatementAccount cur = a;
    Set<Long> seen = new HashSet<>();
    while (cur != null && cur.getParentId() != null && seen.add(cur.getId())) {
      FinancialStatementAccount parent = byId.get(cur.getParentId());
      if (parent == null) {
        return "상위 계정이 없어 트리에서 끊겼습니다. (" + cur.getAccountCode() + " 의 상위)";
      }
      if (!"사용".equals(parent.getIsActive())) {
        return "상위 계정 " + parent.getAccountCode() + " " + parent.getAccountName()
             + " 이(가) '미사용' 이라 하위 금액이 합산되지 않습니다.";
      }
      cur = parent;
    }
    return null;
  }
}
