package com.jdend.erp.legal.repository;

import com.jdend.erp.legal.entity.LegalCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface LegalCaseRepository extends JpaRepository<LegalCase, Long> {

    List<LegalCase> findByContractNumberOrderByIdDesc(String contractNumber);

    @Query("SELECT c FROM LegalCase c WHERE " +
           "(:kw IS NULL OR LOWER(c.contractNumber) LIKE :kw OR LOWER(c.customerName) LIKE :kw) " +
           "AND (:status IS NULL OR c.status = :status) " +
           "ORDER BY c.id DESC")
    List<LegalCase> search(@Param("kw") String kw, @Param("status") String status);
}
