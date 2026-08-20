package com.jdend.erp.contract.repository;

import com.jdend.erp.contract.dto.ContractStatusRowResponse;
import com.jdend.erp.contract.entity.Contract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContractRepository extends JpaRepository<Contract, Long> {

  boolean existsByContractNumber(String contractNumber);

  @Query("select max(c.contractNumber) from Contract c")
  String findMaxContractNumber();

  @Query("SELECT MAX(c.contractNumber) FROM Contract c WHERE c.contractNumber LIKE CONCAT(:prefix, '%')")
  Optional<String> findMaxContractNumberByPrefix(@Param("prefix") String prefix);

  Optional<Contract> findByContractNumber(String contractNumber);

  @Query("""
    select c
    from Contract c
    left join fetch c.customer cust
    where c.contractNumber = :contractNumber
  """)
  Optional<Contract> findWithCustomerByContractNumber(@Param("contractNumber") String contractNumber);

  @Query("""
    select c
    from Contract c
    left join fetch c.customer cust
    where (:kw = '' or
           c.contractNumber like concat('%', :kw, '%') or
           cust.customerName like concat('%', :kw, '%'))
    order by c.id desc
  """)
  List<Contract> searchTop200(@Param("kw") String kw);

  /** 수납 가능한 채권 — 상각·종료된 채권은 제외한다. 해지(기한이익상실) 채권은 계속 회수하므로 포함한다. */
  @Query("""
    select c
    from Contract c
    left join fetch c.customer cust
    where (c.status is null or trim(c.status) = '' or c.status not in ('상각', '종료'))
      and (
        :kw = '' or
        c.contractNumber like concat('%', :kw, '%') or
        cust.customerName like concat('%', :kw, '%')
      )
    order by c.id desc
  """)
  List<Contract> payableSearchTop200(@Param("kw") String kw);

  @Query("""
    select new com.jdend.erp.contract.dto.ContractStatusRowResponse(
      c.contractNumber,
      cust.customerName,
      c.loanType,
      c.status,
      null,
      c.startDate,
      c.endDate,
      c.loanAmount,
      c.interestRate,
      c.monthlyPayment,
      c.remainingPrincipal,
      0L
    )
    from Contract c
    left join c.customer cust
    where 1=1
      and (:contractNumber = '' or lower(c.contractNumber) like concat('%', lower(:contractNumber), '%'))
      and (:customerName = '' or lower(cust.customerName) like concat('%', lower(:customerName), '%'))
    order by c.id desc
  """)
  List<ContractStatusRowResponse> statusList(
      @Param("contractNumber") String contractNumber,
      @Param("customerName") String customerName
  );

  @Query("""
    select c.contractNumber
    from Contract c
    where c.customerNumber = :customerNumber
  """)
  List<String> findContractNumbersByCustomerNumber(@Param("customerNumber") String customerNumber);

  /** 청구생성 대출구분(신용/담보/사업자) 필터용 */
  @Query("""
    select c.contractNumber
    from Contract c
    where c.loanType = :loanType
  """)
  List<String> findContractNumbersByLoanType(@Param("loanType") String loanType);

  /** 연체현황 — 상각·종료 채권은 연체 판정 대상에서 제외한다. */
  @Query("""
    select c
    from Contract c
    left join fetch c.customer cust
    where c.status not in ('상각', '종료')
    order by c.id asc
  """)
  List<Contract> findAllActiveWithCustomer();

  /** 만기 스케줄러: 종료일이 지났고 아직 종료 처리되지 않은 채권 */
  @Query("""
    select c from Contract c
    where c.endDate < :today
      and c.status not in :terminated
  """)
  List<Contract> findExpiredNotClosed(
      @Param("today") LocalDate today,
      @Param("terminated") Collection<String> terminated
  );

  /** 선수금 관리: 지정 ID 목록 중 필터 조건에 맞는 채권 조회 (고객 fetch join) */
  @Query("""
    select c
    from Contract c
    left join fetch c.customer cust
    where c.id in :ids
      and (:customerName = '' or lower(cust.customerName) like concat('%', lower(:customerName), '%'))
      and (:startDate is null or c.endDate >= :startDate)
      and (:endDate is null or c.startDate <= :endDate)
    order by c.id desc
  """)
  List<Contract> findByIdsWithCustomerAndFilter(
      @Param("ids") List<Long> ids,
      @Param("customerName") String customerName,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate
  );
}
