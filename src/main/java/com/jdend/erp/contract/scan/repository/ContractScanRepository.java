package com.jdend.erp.contract.scan.repository;

import com.jdend.erp.contract.scan.entity.ContractScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ContractScanRepository extends JpaRepository<ContractScan, Long> {

  /**
   * 서류일자 최신순. 일자가 없는 건은 뒤로 보낸다.
   *
   * 구분 전체를 볼 때는 category 에 빈 문자열을 넘긴다. null 비교(:category is null)로
   * 하면 파라미터 타입을 추론할 수 없다는 오류가 날 수 있어 빈 문자열로 약속한다.
   */
  @Query("""
      select s from ContractScan s
      where s.contractNumber = :contractNumber
        and (:category = '' or s.docCategory = :category)
      order by s.docDate desc nulls last, s.id desc
      """)
  List<ContractScan> findForContract(@Param("contractNumber") String contractNumber,
                                     @Param("category") String category);

  /**
   * 채권 목록에 붙일 건수 — [계약번호, 서류구분, 건수, 마지막 등록시각].
   *
   * 채권 한 건마다 따로 세면 목록이 200건일 때 질의가 200번 나간다. 한 번에 묶는다.
   */
  @Query("""
      select s.contractNumber, s.docCategory, count(s), max(s.createdAt)
      from ContractScan s
      where s.contractNumber in :contractNumbers
      group by s.contractNumber, s.docCategory
      """)
  List<Object[]> countByContracts(@Param("contractNumbers") Collection<String> contractNumbers);

  /** 스캔이 하나라도 있는 계약번호 — '스캔 있는 건만' 보기에 쓴다. */
  @Query("select distinct s.contractNumber from ContractScan s")
  List<String> findContractNumbersWithScan();

  long countByContractNumber(String contractNumber);
}
