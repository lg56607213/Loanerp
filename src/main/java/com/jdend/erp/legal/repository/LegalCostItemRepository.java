package com.jdend.erp.legal.repository;

import com.jdend.erp.legal.entity.LegalCostItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface LegalCostItemRepository extends JpaRepository<LegalCostItem, Long> {
    List<LegalCostItem> findByLegalCaseIdOrderByCostDateAscIdAsc(Long legalCaseId);
    void deleteByLegalCaseId(Long legalCaseId);

    /**
     * 채권 단위 법적비용 총액 — 변제충당 1순위 '법적비용'의 원천.
     *
     * 포함 항목은 legal(법적절차) 모듈이 기록한 비용 전부다.
     *   신청비용 / 추가비용 / 확인비용  → 채무자에게 청구할 비용 (+)
     *   환입                            → 법원 등에서 돌려받아 이미 회수된 금액 (−)
     *
     * 여기서 나온 총액에서 이미 충당된 금액을 빼면 미회수 법적비용이 된다.
     *
     * asOf 로 비용 발생일을 자른다. 수납은 처음부터 다시 흘려보내며(replay) 충당을 재계산하는데,
     * 날짜를 자르지 않으면 나중에 등록한 비용이 그 이전 수납에까지 소급 적용된다.
     * 그러면 이미 완납이던 회차가 부분납으로 되돌아가 없던 지연배상금이 붙는다.
     */
    @Query("select coalesce(sum(case when i.costType = '환입' then (0 - i.amount) else i.amount end), 0) " +
           "from LegalCostItem i, LegalCase c " +
           "where c.id = i.legalCaseId and c.contractNumber = :contractNumber " +
           "  and i.costDate <= :asOf")
    Long sumChargeableByContractNumberAsOf(@Param("contractNumber") String contractNumber,
                                           @Param("asOf") LocalDate asOf);
}
