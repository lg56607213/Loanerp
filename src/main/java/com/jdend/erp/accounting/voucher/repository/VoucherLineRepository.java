package com.jdend.erp.accounting.voucher.repository;

import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import com.jdend.erp.management.financial.dto.FinancialStatementVoucherRowResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface VoucherLineRepository extends JpaRepository<VoucherLine, Long> {

  @Query("""
      select new com.jdend.erp.management.financial.dto.FinancialStatementVoucherRowResponse(
          v.id,
          v.voucherDate,
          v.voucherNo,
          l.lineType,
          l.accountName,
          l.amount,
          l.description,
          v.contractNumber,
          v.memo,
          v.status
      )
      from VoucherLine l
      join l.voucher v
      where l.accountName = :accountName
        and (:startDate is null or v.voucherDate >= :startDate)
        and (:endDate is null or v.voucherDate <= :endDate)
      order by v.voucherDate desc, v.id desc, l.sortOrder asc
  """)
  List<FinancialStatementVoucherRowResponse> findVoucherRowsByAccountNameAndDateRange(
      @Param("accountName") String accountName,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate
  );

  boolean existsByAccountName(String accountName);

  @Query("""
      select l from VoucherLine l join fetch l.voucher v
      where l.lineType = 'CREDIT'
      and (l.accountName like '%미지급%' or l.accountName like '%법인카드%')
      and l.paid = false
      and v.status in ('승인', '대기')
      and (:startDate is null or v.voucherDate >= :startDate)
      and (:endDate is null or v.voucherDate <= :endDate)
      and (:accountName is null or :accountName = '' or l.accountName = :accountName)
      order by v.voucherDate desc, v.id desc
  """)
  List<VoucherLine> findPayables(
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate,
      @Param("accountName") String accountName
  );

  @Query("""
      select distinct l.accountName from VoucherLine l
      where l.lineType = 'CREDIT'
      and (l.accountName like '%미지급%' or l.accountName like '%법인카드%')
      and l.paid = false
      order by l.accountName
  """)
  List<String> findPayableAccountNames();
}