package com.jdend.erp.payment.billing.repository;

import com.jdend.erp.payment.billing.entity.PaymentSchedules;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PaymentSchedulesRepository extends JpaRepository<PaymentSchedules, Long> {

  @Query("""
    select ps
    from PaymentSchedules ps
    where ps.taxInvoiceDate between :start and :end
  """)
  List<PaymentSchedules> findByTaxInvoiceDateBetween(@Param("start") LocalDate start,
                                                     @Param("end") LocalDate end);

  @Query("""
    select ps
    from PaymentSchedules ps
    where ps.taxInvoiceDate between :start and :end
      and ps.contractNumber in :contractNumbers
  """)
  List<PaymentSchedules> findByTaxInvoiceDateBetweenAndContractNumbers(@Param("start") LocalDate start,
                                                                       @Param("end") LocalDate end,
                                                                       @Param("contractNumbers") List<String> contractNumbers);

  /** 수납일자 이전(포함) 미납 스케줄 — 수익 분개 및 납부 처리용 */
  @Query("""
    select ps
    from PaymentSchedules ps
    where ps.contractNumber = :contractNumber
      and ps.taxInvoiceDate <= :asOfDate
      and ps.paymentDate is null
    order by ps.taxInvoiceDate asc, ps.id asc
  """)
  List<PaymentSchedules> findUnpaidByContractNumberAndDateLTE(
      @Param("contractNumber") String contractNumber,
      @Param("asOfDate") LocalDate asOfDate
  );

  /** 납부 처리된 스케줄 역순 — 수납 삭제/수정 시 복구용 */
  @Query("""
    select ps
    from PaymentSchedules ps
    where ps.contractNumber = :contractNumber
      and ps.paymentDate is not null
    order by ps.taxInvoiceDate desc, ps.id desc
  """)
  List<PaymentSchedules> findPaidByContractNumberOrderByDateDesc(
      @Param("contractNumber") String contractNumber
  );

  /** 계약의 전체 스케줄 조회 — 선수금 수납 화면용 */
  @Query("""
    select ps
    from PaymentSchedules ps
    where ps.contractNumber = :contractNumber
    order by ps.taxInvoiceDate asc, ps.id asc
  """)
  List<PaymentSchedules> findAllByContractNumberOrdered(
      @Param("contractNumber") String contractNumber
  );
}