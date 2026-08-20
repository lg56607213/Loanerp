package com.jdend.erp.payment.schedule.repository;

import com.jdend.erp.payment.schedule.entity.PaymentSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PaymentScheduleRepository extends JpaRepository<PaymentSchedule, Long> {

  List<PaymentSchedule> findByContractNumberOrderByInstallmentNoAsc(String contractNumber);

  Optional<PaymentSchedule> findByContractNumberAndInstallmentNo(String contractNumber, Integer installmentNo);

  boolean existsByContractNumber(String contractNumber);

  long countByContractNumber(String contractNumber);

  // ✅ 청구생성 핵심: 세금계산서일자 기간
  List<PaymentSchedule> findByTaxInvoiceDateBetween(LocalDate taxStartDate, LocalDate taxEndDate);

  // BUG-05: 계약 수정 시 미래 미수납 스케줄만 삭제 (billStartDate >= 기준일인 건)
  @Modifying
  @Query("delete from PaymentSchedule ps where ps.contractNumber = :contractNumber and ps.billStartDate >= :fromDate")
  int deleteByContractNumberAndBillStartDateGreaterThanEqual(@Param("contractNumber") String contractNumber, @Param("fromDate") LocalDate fromDate);

  // BUG-07: 연체 조회 N+1 개선 - 계약번호 목록으로 일괄 조회
  @Query("select ps from PaymentSchedule ps where ps.contractNumber in :contractNumbers order by ps.contractNumber asc, ps.installmentNo asc")
  List<PaymentSchedule> findByContractNumberIn(@Param("contractNumbers") List<String> contractNumbers);

  // 수납 전표 분기용: 납기일(taxInvoiceDate)이 기준일 이전인 스케줄 조회
  @Query("SELECT ps FROM PaymentSchedule ps WHERE ps.contractNumber = :cn AND ps.taxInvoiceDate <= :asOf ORDER BY ps.taxInvoiceDate ASC")
  List<PaymentSchedule> findDueByContractNumberAndTaxInvoiceDateLTE(@Param("cn") String cn, @Param("asOf") LocalDate asOf);

  // [통합] PaymentSchedules 병합: 기간 내 스케줄 + 계약번호 목록 필터
  @Query("select ps from PaymentSchedule ps where ps.taxInvoiceDate between :start and :end and ps.contractNumber in :contractNumbers")
  List<PaymentSchedule> findByTaxInvoiceDateBetweenAndContractNumbers(
      @Param("start") LocalDate start,
      @Param("end") LocalDate end,
      @Param("contractNumbers") List<String> contractNumbers
  );

  // [통합] 수납일자 이전(포함) 미납 스케줄 — 수익 분개 및 납부 처리용
  @Query("""
      select ps from PaymentSchedule ps
      where ps.contractNumber = :contractNumber
        and ps.taxInvoiceDate <= :asOfDate
        and ps.paymentDate is null
      order by ps.taxInvoiceDate asc, ps.id asc
  """)
  List<PaymentSchedule> findUnpaidByContractNumberAndDateLTE(
      @Param("contractNumber") String contractNumber,
      @Param("asOfDate") LocalDate asOfDate
  );

  // [통합] 납부 처리된 스케줄 역순 — 수납 삭제/수정 시 복구용
  @Query("""
      select ps from PaymentSchedule ps
      where ps.contractNumber = :contractNumber
        and ps.paymentDate is not null
      order by ps.taxInvoiceDate desc, ps.id desc
  """)
  List<PaymentSchedule> findPaidByContractNumberOrderByDateDesc(
      @Param("contractNumber") String contractNumber
  );

  // [통합] 계약의 전체 스케줄 조회 — 선수금 수납 화면용
  @Query("""
      select ps from PaymentSchedule ps
      where ps.contractNumber = :contractNumber
      order by ps.taxInvoiceDate asc, ps.id asc
  """)
  List<PaymentSchedule> findAllByContractNumberOrdered(
      @Param("contractNumber") String contractNumber
  );
}