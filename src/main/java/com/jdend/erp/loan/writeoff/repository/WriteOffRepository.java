package com.jdend.erp.loan.writeoff.repository;

import com.jdend.erp.loan.writeoff.entity.WriteOff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WriteOffRepository extends JpaRepository<WriteOff, Long> {

  Optional<WriteOff> findFirstByContractNumberOrderByIdDesc(String contractNumber);

  boolean existsByContractNumber(String contractNumber);

  @Query("""
    select w from WriteOff w
    where (:kw = '' or lower(w.contractNumber) like concat('%', lower(:kw), '%')
                    or lower(w.customerName) like concat('%', lower(:kw), '%'))
    order by w.writeOffDate desc, w.id desc
  """)
  List<WriteOff> search(@Param("kw") String kw);
}
