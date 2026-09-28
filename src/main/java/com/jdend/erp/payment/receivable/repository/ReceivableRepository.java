package com.jdend.erp.payment.receivable.repository;

import com.jdend.erp.payment.receivable.entity.Receivable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ReceivableRepository extends JpaRepository<Receivable, Long> {

  /** 채권 삭제 시 함께 정리한다. */
  void deleteByContractNumber(String contractNumber);

  /** BUG-10: 계약번호 + 상태로 미수금 조회 (수납 등록 시 완납 처리에 사용) */
  @Query("select r from Receivable r where r.contractNumber = :contractNumber and r.status = :status order by r.receivableDate asc, r.id asc")
  List<Receivable> findByContractNumberAndStatus(@Param("contractNumber") String contractNumber, @Param("status") String status);

  /** 납기일 이전(포함) 미납 미수금 조회 — 초과수납 판별 및 완납 처리에 사용 */
  @Query("select r from Receivable r where r.contractNumber = :contractNumber and r.status = :status and r.receivableDate <= :asOfDate order by r.receivableDate asc, r.id asc")
  List<Receivable> findByContractNumberAndStatusAndReceivableDateLTE(
      @Param("contractNumber") String contractNumber,
      @Param("status") String status,
      @Param("asOfDate") LocalDate asOfDate
  );

  /** BUG-03: 수납 삭제/수정 시 완납 상태 역순 복구용 (가장 최근 완납 순) */
  @Query("select r from Receivable r where r.contractNumber = :contractNumber and r.status = :status order by r.receivableDate desc, r.id desc")
  List<Receivable> findByContractNumberAndStatusOrderByIdDesc(@Param("contractNumber") String contractNumber, @Param("status") String status);

  /** 수납 취소 시 완납·완료 모두 복구 대상으로 조회 */
  @Query("select r from Receivable r where r.contractNumber = :contractNumber and r.status in :statuses order by r.receivableDate desc, r.id desc")
  List<Receivable> findByContractNumberAndStatusInOrderByIdDesc(@Param("contractNumber") String contractNumber, @Param("statuses") List<String> statuses);

  @Query("""
    select r
    from Receivable r
    where
      (:startDate is null or r.receivableDate >= :startDate)
      and (:endDate is null or r.receivableDate <= :endDate)
      and (:customerName = '' or lower(r.customerName) like concat('%', lower(:customerName), '%'))
      and (:status = '' or r.status = :status)
    order by r.id desc
  """)
  List<Receivable> search(
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate,
      @Param("customerName") String customerName,
      @Param("status") String status
  );
}