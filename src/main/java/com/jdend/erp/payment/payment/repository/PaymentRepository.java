package com.jdend.erp.payment.payment.repository;

import com.jdend.erp.payment.payment.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  @Query("""
    select p
    from Payment p
    where (:kw is null or :kw = ''
      or p.contractNumber like concat('%', :kw, '%')
      or p.customerName like concat('%', :kw, '%')
    )
    order by p.id desc
  """)
  Page<Payment> search(@Param("kw") String kw, Pageable pageable);

  @Query("""
    select p
    from Payment p
    where p.contractNumber = :contractNumber
    order by p.paymentDate asc, p.id asc
  """)
  List<Payment> findByContractNumberOrderByPaymentDateAscIdAsc(@Param("contractNumber") String contractNumber);

  /** BUG-07: 연체 조회 N+1 개선 - 계약번호 목록으로 일괄 조회 */
  @Query("""
    select p
    from Payment p
    where p.contractNumber in :contractNumbers
    order by p.contractNumber asc, p.paymentDate asc, p.id asc
  """)
  List<Payment> findByContractNumberIn(@Param("contractNumbers") List<String> contractNumbers);

  /** 전표 삭제 시 연결된 수납 일괄 조회 */
  List<Payment> findByVoucherIdIn(List<Long> voucherIds);
}