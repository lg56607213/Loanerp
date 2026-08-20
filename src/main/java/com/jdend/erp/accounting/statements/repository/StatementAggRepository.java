package com.jdend.erp.accounting.statements.repository;

import com.jdend.erp.accounting.statements.dto.BalanceDetailRowResponse;
import com.jdend.erp.accounting.voucher.entity.VoucherLine;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface StatementAggRepository extends JpaRepository<VoucherLine, Long> {

  interface LineSumRow {
    String getAccountCode(); // 계정코드 기준 집계
    String getLineType();    // DEBIT / CREDIT
    Long getAmt();
  }

  // BUG-04 수정: accountCode가 null인 자동전표 라인도 재무제표에 포함되도록
  // "and l.accountCode is not null" 조건 제거 — null 계정코드는 null 그룹으로 집계됨
  @Query("""
    select l.accountCode as accountCode,
           l.lineType as lineType,
           sum(l.amount) as amt
      from VoucherLine l
      join l.voucher v
     where v.voucherDate between :start and :end
       and (:status is null or :status = '' or v.status = :status)
     group by l.accountCode, l.lineType
  """)
  List<LineSumRow> sumByAccountBetween(
      @Param("start") LocalDate start,
      @Param("end") LocalDate end,
      @Param("status") String status
  );

  @Query("""
    select l.accountCode as accountCode,
           l.lineType as lineType,
           sum(l.amount) as amt
      from VoucherLine l
      join l.voucher v
     where v.voucherDate <= :ref
       and (:status is null or :status = '' or v.status = :status)
     group by l.accountCode, l.lineType
  """)
  List<LineSumRow> sumByAccountToDate(
      @Param("ref") LocalDate ref,
      @Param("status") String status
  );

  @Query("""
    select new com.jdend.erp.accounting.statements.dto.BalanceDetailRowResponse(
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
    where (:startDate is null or v.voucherDate >= :startDate)
      and v.voucherDate <= :referenceDate
      and l.accountCode in :accountCodes
      and (:status is null or :status = '' or v.status = :status)
    order by v.voucherDate desc, v.id desc, l.sortOrder asc
  """)
  List<BalanceDetailRowResponse> findBalanceDetails(
      @Param("startDate") LocalDate startDate,
      @Param("referenceDate") LocalDate referenceDate,
      @Param("accountCodes") List<String> accountCodes,
      @Param("status") String status
  );
}