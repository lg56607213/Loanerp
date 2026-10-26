package com.jdend.erp.accounting.taxinvoice.repository;

import com.jdend.erp.accounting.taxinvoice.entity.TaxInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TaxInvoiceRepository extends JpaRepository<TaxInvoice, Long> {

  @Query("""
    select t from TaxInvoice t
    where (:invoiceType = '' or t.invoiceType = :invoiceType)
      and (:kw = '' or t.partnerName like concat('%', :kw, '%')
                     or t.item like concat('%', :kw, '%'))
      and (:startDate is null or t.issueDate >= :startDate)
      and (:endDate   is null or t.issueDate <= :endDate)
    order by t.issueDate asc, t.id asc
  """)
  List<TaxInvoice> search(@Param("invoiceType") String invoiceType,
                          @Param("kw") String kw,
                          @Param("startDate") LocalDate startDate,
                          @Param("endDate") LocalDate endDate);

  boolean existsByRowHash(String rowHash);

  boolean existsByApprovalNo(String approvalNo);

  @Query("""
    select t from TaxInvoice t
    where t.invoiceType = :invoiceType
      and t.issueDate between :startDate and :endDate
    order by t.issueDate asc, t.id asc
  """)
  List<TaxInvoice> findForReconcile(@Param("invoiceType") String invoiceType,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate);
}
