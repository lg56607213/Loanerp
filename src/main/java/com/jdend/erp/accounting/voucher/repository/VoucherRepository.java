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

    /**
     * 전표승인 화면 검색 — 기간·상태에 더해 전표번호·채권번호·적요로 찾는다.
     *
     * 검색어를 주면 <b>조회기간을 무시한다</b>. 대출 실행 전표의 일자는 실행일이라
     * 몇 달 전일 수 있는데, 기간에 걸려 찾지 못하면 승인을 취소할 방법이 없어진다.
     * 번호를 알고 찾는 경우에는 기간이 방해만 된다.
     */
    @Query("""
        select v
        from Voucher v
        where (
                (:kw is not null and :kw <> '')
                or ((:startDate is null or v.voucherDate >= :startDate)
                    and (:endDate is null or v.voucherDate <= :endDate))
              )
          and (:status is null or :status = '' or v.status = :status)
          and (:kw is null or :kw = ''
               or v.voucherNo like concat('%', :kw, '%')
               or v.contractNumber like concat('%', :kw, '%')
               or v.memo like concat('%', :kw, '%'))
        order by v.voucherDate desc, v.id desc
    """)
    List<Voucher> searchForApprovalRange(@Param("startDate") LocalDate startDate,
                                         @Param("endDate") LocalDate endDate,
                                         @Param("status") String status,
                                         @Param("kw") String kw);

    @Query("select v from Voucher v where v.voucherDate = :date and v.totalAmount = :amount and v.memo like concat(:prefix, '%') order by v.id asc")
    List<Voucher> findByVoucherDateAndAmountAndMemoPrefix(@Param("date") LocalDate date, @Param("amount") Long amount, @Param("prefix") String prefix);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Voucher v
           set v.status = '승인'
         where v.id in :ids
    """)
    int approveByIds(@Param("ids") List<Long> ids);

    /** 승인된 전표를 대기로 되돌린다. 원본(수납·대출 등)을 취소하려면 먼저 이 상태여야 한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Voucher v
           set v.status = '대기'
         where v.id in :ids
    """)
    int unapproveByIds(@Param("ids") List<Long> ids);

    /** 이 채권의 전표 중 승인 상태인 것이 있는지 — 원본 취소를 막는 판단에 쓴다. */
    boolean existsByContractNumberAndStatus(String contractNumber, String status);

}