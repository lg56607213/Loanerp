package com.jdend.erp.payment.consultation.repository;

import com.jdend.erp.payment.consultation.entity.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConsultationRepository extends JpaRepository<Consultation, Long> {

  /** 채권 삭제 시 함께 정리한다. */
  void deleteByContractNumber(String contractNumber);

  @Query("""
    select c
    from Consultation c
    where c.contractNumber = :contractNumber
    order by c.consultDate desc, c.id desc
  """)
  List<Consultation> findByContractNumberOrder(@Param("contractNumber") String contractNumber);
}