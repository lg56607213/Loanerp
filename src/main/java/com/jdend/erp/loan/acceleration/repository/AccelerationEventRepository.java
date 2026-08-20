package com.jdend.erp.loan.acceleration.repository;

import com.jdend.erp.loan.acceleration.entity.AccelerationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccelerationEventRepository extends JpaRepository<AccelerationEvent, Long> {

  Optional<AccelerationEvent> findFirstByContractNumberOrderByIdDesc(String contractNumber);

  boolean existsByContractNumber(String contractNumber);

  @Query("""
    select e from AccelerationEvent e
    where (:kw = '' or lower(e.contractNumber) like concat('%', lower(:kw), '%')
                    or lower(e.customerName) like concat('%', lower(:kw), '%'))
    order by e.eodDate desc, e.id desc
  """)
  List<AccelerationEvent> search(@Param("kw") String kw);
}
