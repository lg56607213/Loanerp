package com.jdend.erp.legal.repository;

import com.jdend.erp.legal.entity.LegalCostItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * 여기서 나온 총액에서 이미 충당된 금액(스케줄 paid_cost 합계)을 빼면 미회수 법적비용이 된다.
     */
    @Query("select coalesce(sum(case when i.costType = '환입' then (0 - i.amount) else i.amount end), 0) " +
           "from LegalCostItem i, LegalCase c " +
           "where c.id = i.legalCaseId and c.contractNumber = :contractNumber")
    Long sumChargeableByContractNumber(@Param("contractNumber") String contractNumber);
}
