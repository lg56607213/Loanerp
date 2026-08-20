package com.jdend.erp.management.financial.service;

import com.jdend.erp.accounting.voucher.repository.VoucherLineRepository;
import com.jdend.erp.management.financial.dto.FinancialStatementAccountRequest;
import com.jdend.erp.management.financial.dto.FinancialStatementAccountResponse;
import com.jdend.erp.management.financial.dto.FinancialStatementAccountTreeResponse;
import com.jdend.erp.management.financial.dto.FinancialStatementVoucherRowResponse;
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
public class FinancialStatementAccountService {

  private final FinancialStatementAccountRepository repo;
  private final VoucherLineRepository voucherLineRepository;

  private static final Map<String, String> ROOT_CODE = Map.of(
      "ASSET", "10",
      "LIABILITY", "20",
      "EQUITY", "30",
      "REVENUE", "40",
      "EXPENSE", "50"
  );

  private static final Map<String, String> CATEGORY_LABEL = Map.of(
      "ASSET", "자산",
      "LIABILITY", "부채",
      "EQUITY", "자본",
      "REVENUE", "수익",
      "EXPENSE", "비용"
  );

  private FinancialStatementAccountResponse toRes(FinancialStatementAccount e) {
    return toRes(e, null);
  }

  private FinancialStatementAccountResponse toRes(FinancialStatementAccount e, String parentName) {
    return FinancialStatementAccountResponse.builder()
        .id(e.getId())
        .statementType(e.getStatementType())
        .accountCode(e.getAccountCode())
        .accountName(e.getAccountName())
        .accountType(e.getAccountType())
        .displayOrder(e.getDisplayOrder())
        .isActive(e.getIsActive())
        .category(e.getCategory())
        .level(e.getLevel())
        .parentId(e.getParentId())
        .isPostable(e.getIsPostable())
        .parentName(parentName)
        .build();
  }

  @Transactional(readOnly = true)
  public List<FinancialStatementAccountResponse> list(String statementType) {
    return repo.findByStatementTypeOrderByDisplayOrderAsc(statementType)
        .stream()
        .map(this::toRes)
        .toList();
  }

  // 평면 구조 시절 등록 API. 계층(대/중/소/소소분류)과 계정코드 자동생성 규칙을 우회하므로
  // 더 이상 사용하지 않는다. 신규 계정은 재무제표관리 화면(createNode)에서 등록한다.
  @Transactional
  public FinancialStatementAccountResponse create(FinancialStatementAccountRequest req) {
    throw new IllegalStateException("계정 등록은 재무제표관리 화면에서 상위 분류를 선택해 추가해주세요.");
  }

  // 계정코드/대분류/레벨/상위분류는 트리 구조의 정합성을 위해 수정 불가. 이름/사용여부/전기가능여부만 변경 가능.
  @Transactional
  public FinancialStatementAccountResponse update(Long id, FinancialStatementAccountRequest req) {
    FinancialStatementAccount e = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 ID: " + id));

    e.setAccountName(req.getAccountName());
    if (req.getAccountType() != null) {
      e.setAccountType(req.getAccountType());
    }
    if (req.getIsActive() != null) {
      e.setIsActive(req.getIsActive());
    }
    if (req.getIsPostable() != null) {
      e.setIsPostable(req.getIsPostable());
    }

    return toRes(e);
  }

  @Transactional
  public void delete(Long id) {
    if (repo.existsByParentId(id)) {
      throw new IllegalArgumentException("하위 분류가 있는 계정은 삭제할 수 없습니다.");
    }

    FinancialStatementAccount account = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 계정 ID: " + id));

    // 이미 전표에서 사용 중인 계정을 지우면 그 전표들이 존재하지 않는 계정을 참조하게 되어
    // 재무제표 집계에서 누락된다. 사용 이력이 있으면 삭제 대신 미사용 처리를 안내한다.
    if (voucherLineRepository.existsByAccountName(account.getAccountName())) {
      throw new IllegalArgumentException(
          "이미 전표에서 사용된 계정은 삭제할 수 없습니다. 더 이상 쓰지 않으려면 전기가능을 '미사용'으로 변경해주세요.");
    }

    repo.deleteById(id);
  }

  @Transactional(readOnly = true)
  public List<FinancialStatementVoucherRowResponse> getVoucherRows(
      Long accountId,
      LocalDate startDate,
      LocalDate endDate
  ) {
    FinancialStatementAccount account = repo.findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 계정 ID: " + accountId));

    return voucherLineRepository.findVoucherRowsByAccountNameAndDateRange(
        account.getAccountName(),
        startDate,
        endDate
    );
  }

  // ==========================
  // 계층 구조: 노드 생성 (계정코드 자동 부여)
  // ==========================
  @Transactional
  public FinancialStatementAccountResponse createNode(FinancialStatementAccountRequest req) {
    Long parentId = req.getParentId();
    String accountCode;
    Integer level;
    String category;
    String statementType;

    if (parentId == null) {
      category = req.getCategory();
      if (category == null || !ROOT_CODE.containsKey(category)) {
        throw new IllegalArgumentException("대분류 등록 시 category가 필요합니다: ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE");
      }
      level = 1;
      accountCode = ROOT_CODE.get(category);
      statementType = category.equals("ASSET") || category.equals("LIABILITY") || category.equals("EQUITY") ? "bs" : "is";
    } else {
      FinancialStatementAccount parent = repo.findById(parentId)
          .orElseThrow(() -> new IllegalArgumentException("상위 분류를 찾을 수 없습니다: " + parentId));
      if (parent.getLevel() >= 4) {
        throw new IllegalArgumentException("소소분류 하위에는 추가할 수 없습니다.");
      }
      level = parent.getLevel() + 1;
      category = parent.getCategory();
      statementType = parent.getStatementType();
      long seq = repo.countByParentId(parentId) + 1;
      accountCode = parent.getAccountCode() + String.format("%02d", seq);
    }

    FinancialStatementAccount saved = repo.save(
        FinancialStatementAccount.builder()
            .statementType(statementType)
            .category(category)
            .level(level)
            .parentId(parentId)
            .accountCode(accountCode)
            .accountName(req.getAccountName())
            .accountType(req.getAccountType() != null ? req.getAccountType() : req.getAccountName())
            .displayOrder((int) repo.countByParentId(parentId))
            .isActive(req.getIsActive() != null ? req.getIsActive() : "사용")
            .isPostable(req.getIsPostable() != null ? req.getIsPostable() : "사용")
            .build()
    );

    return toRes(saved);
  }

  private void ensureRoot(String category) {
    String rootCode = ROOT_CODE.get(category);
    if (rootCode == null) {
      throw new IllegalArgumentException("지원하지 않는 카테고리입니다: " + category);
    }
    if (repo.existsByAccountCode(rootCode)) {
      return;
    }

    String statementType = ("ASSET".equals(category) || "LIABILITY".equals(category) || "EQUITY".equals(category))
        ? "bs" : "is";
    String label = CATEGORY_LABEL.get(category);

    repo.save(
        FinancialStatementAccount.builder()
            .statementType(statementType)
            .category(category)
            .level(1)
            .parentId(null)
            .accountCode(rootCode)
            .accountName(label)
            .accountType(label)
            .displayOrder(Integer.parseInt(rootCode) / 10)
            .isActive("사용")
            .isPostable("미사용")
            .build()
    );
  }

  // ==========================
  // 계층 구조: 카테고리별 트리 조회
  // ==========================
  // 신규 회사(테넌트) DB는 테이블 구조만 복제되고 데이터가 비어있어 대분류 자체가 없을 수 있다.
  // 그 상태로는 화면에 "하위분류추가"를 누를 노드가 하나도 없어 막히므로, 조회 시점에 표준 대분류를
  // 자동으로 만들어준다(자산/부채/자본/수익/비용, 코드 10/20/30/40/50 고정).


  @Transactional
  public List<FinancialStatementAccountTreeResponse> tree(String category) {
    ensureRoot(category);

    List<FinancialStatementAccount> all = repo.findByCategoryOrderByAccountCodeAsc(category);

    Map<Long, List<FinancialStatementAccount>> byParent = all.stream()
        .filter(a -> a.getParentId() != null)
        .collect(Collectors.groupingBy(FinancialStatementAccount::getParentId));

    return all.stream()
        .filter(a -> a.getParentId() == null)
        .map(root -> buildNode(root, byParent, new java.util.HashSet<>()))
        .toList();
  }

  private FinancialStatementAccountTreeResponse buildNode(
      FinancialStatementAccount node,
      Map<Long, List<FinancialStatementAccount>> byParent,
      java.util.Set<Long> visiting
  ) {
    // parentId가 순환을 이루면 무한 재귀로 서버가 죽으므로, 방문 중인 노드를 추적해 끊는다.
    if (!visiting.add(node.getId())) {
      throw new IllegalStateException("계정 트리에 순환 참조가 있습니다. id=" + node.getId());
    }

    List<FinancialStatementAccount> childEntities = byParent.getOrDefault(node.getId(), List.of());
    List<FinancialStatementAccountTreeResponse> children = childEntities.stream()
        .map(c -> buildNode(c, byParent, visiting))
        .toList();

    visiting.remove(node.getId());

    return FinancialStatementAccountTreeResponse.builder()
        .id(node.getId())
        .accountCode(node.getAccountCode())
        .accountName(node.getAccountName())
        .level(node.getLevel())
        .isActive(node.getIsActive())
        .isPostable(node.getIsPostable())
        .leaf(children.isEmpty())
        .children(children)
        .build();
  }

  // ==========================
  // 전표등록 select용: 전기가능 + 사용 leaf 전체
  // ==========================
  @Transactional(readOnly = true)
  public List<FinancialStatementAccountResponse> leavesForVoucher() {
    List<FinancialStatementAccount> all = repo.findAll();

    Map<Long, String> idToName = all.stream()
        .collect(Collectors.toMap(FinancialStatementAccount::getId, FinancialStatementAccount::getAccountName));

    return all.stream()
        .filter(a -> "사용".equals(a.getIsPostable()) && "사용".equals(a.getIsActive()))
        .sorted(Comparator.comparing(FinancialStatementAccount::getAccountCode))
        .map(a -> toRes(a, a.getParentId() != null ? idToName.get(a.getParentId()) : null))
        .toList();
  }
}
