package com.jdend.erp.accounting.partnerbalance.repository;

import com.jdend.erp.accounting.partnerbalance.entity.PartnerBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PartnerBalanceRepository extends JpaRepository<PartnerBalance, Long> {

  @Query("""
    select p from PartnerBalance p
    where (:balanceType = '' or p.balanceType = :balanceType)
      and (:kw = '' or p.partnerName like concat('%', :kw, '%')
                     or p.item like concat('%', :kw, '%'))
      and (:startDate is null or p.occurredDate >= :startDate)
      and (:endDate   is null or p.occurredDate <= :endDate)
    order by p.occurredDate asc, p.id asc
  """)
  List<PartnerBalance> search(@Param("balanceType") String balanceType,
                              @Param("kw") String kw,
                              @Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate);

  boolean existsByRowHash(String rowHash);
}
