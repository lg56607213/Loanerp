package com.jdend.erp.accounting.monthlyvoucher.repository;

import com.jdend.erp.accounting.voucher.entity.MonthlyVoucherRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MonthlyVoucherRuleRepository extends JpaRepository<MonthlyVoucherRule, Long> {
  List<MonthlyVoucherRule> findByActiveTrueAndNextRunDateLessThanEqual(LocalDate date);

  @Query("SELECT r FROM MonthlyVoucherRule r WHERE " +
      "(:activeOnly IS NULL OR r.active = :activeOnly) AND " +
      "(:contractNumber IS NULL OR r.contractNumber LIKE %:contractNumber%) " +
      "ORDER BY r.id DESC")
  List<MonthlyVoucherRule> search(
      @Param("activeOnly") Boolean activeOnly,
      @Param("contractNumber") String contractNumber
  );
}