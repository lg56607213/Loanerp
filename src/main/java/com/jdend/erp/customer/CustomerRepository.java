package com.jdend.erp.customer;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

  Optional<Customer> findByCustomerNumber(String customerNumber);

  /** 사업자등록번호(개인은 주민등록번호)로 찾는다. 유니크 제약이 걸린 열이다. */
  Optional<Customer> findByRegistrationNumber(String registrationNumber);

  @Query("select max(c.customerNumber) from Customer c")
  String findMaxCustomerNumber();

  List<Customer> findTop200ByOrderByIdDesc();

  @Query("""
    select c
    from Customer c
    where (:kw = '' or
      c.customerNumber like concat('%', :kw, '%') or
      c.customerName like concat('%', :kw, '%') or
      c.phone like concat('%', :kw, '%'))
    order by c.id desc
  """)
  List<Customer> searchTop200(@Param("kw") String kw);
}