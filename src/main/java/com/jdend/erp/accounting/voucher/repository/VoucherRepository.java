package com.jdend.erp.accounting.voucher.repository;

import com.jdend.erp.accounting.voucher.entity.Voucher;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    boolean existsByVoucherNo(String voucherNo);

    java.util.Optional<Voucher> findByVoucherNo(String voucherNo);

    @Query("select count(v) from Voucher v where v.voucherDate = :date")
    long countByVoucherDate(@Param("date") LocalDate date);

    // BUG-7차-02: 삭제 후 번호 재사용 방지 — 해당 날짜의 최대 시퀀스 조회 (13자리 숫자 전표번호만 대상)
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(voucher_no, 9) AS UNSIGNED)), 0) FROM vouchers WHERE LEFT(voucher_no, 8) = :datePrefix AND voucher_no REGEXP '^[0-9]{13}$'", nativeQuery = true)
    Long findMaxSequenceForDatePrefix(@Param("datePrefix") String datePrefix);

    List<Voucher> findByMemoStartingWithOrderByIdAsc(String memoPrefix);


    @Query("select v from Voucher v where v.contractNumber = :contractNumber and v.memo = :memo order by v.id asc")
    List<Voucher> findByContractNumberAndMemo(@Param("contractNumber") String contractNumber, @Param("memo") String memo);

    @Query("""
        select v
        from Voucher v
        where (:date is null or v.voucherDate = :date)
          and (:status is null or :status = '' or v.status = :status)
        order by v.voucherDate desc, v.id desc
    """)
    List<Voucher> searchForApproval(@Param("date") LocalDate date, @Param("status") String status);

    @Query("""
        select v
        from Voucher v
        where (:startDate is null or v.voucherDate >= :startDate)
          and (:endDate is null or v.voucherDate <= :endDate)
          and (:status is null or :status = '' or v.status = :status)
        order by v.voucherDate desc, v.id desc
    """)
    List<Voucher> searchForApprovalRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("status") String status);

    @Query("select v from Voucher v where v.voucherDate = :date and v.totalAmount = :amount and v.memo like concat(:prefix, '%') order by v.id asc")
    List<Voucher> findByVoucherDateAndAmountAndMemoPrefix(@Param("date") LocalDate date, @Param("amount") Long amount, @Param("prefix") String prefix);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Voucher v
           set v.status = '승인'
         where v.id in :ids
    """)
    int approveByIds(@Param("ids") List<Long> ids);

}