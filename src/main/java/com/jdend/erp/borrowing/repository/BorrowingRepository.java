package com.jdend.erp.borrowing.repository;

import com.jdend.erp.borrowing.entity.Borrowing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BorrowingRepository extends JpaRepository<Borrowing, Long> {

  Optional<Borrowing> findByBorrowingNumber(String borrowingNumber);

  boolean existsByBorrowingNumber(String borrowingNumber);

  List<Borrowing> findAllByOrderByBorrowDateDescIdDesc();

  /** 차입번호 채번용 — 그 날짜 접두사로 시작하는 마지막 번호 */
  @Query("select max(b.borrowingNumber) from Borrowing b where b.borrowingNumber like :prefix%")
  String findMaxNumberByPrefix(@Param("prefix") String prefix);

  /** 차입처·차입번호로 찾는다. 키워드가 비면 전체. */
  @Query("""
      select b from Borrowing b
      where :kw = '' or b.lenderName like %:kw% or b.borrowingNumber like %:kw%
      order by b.borrowDate desc, b.id desc
      """)
  List<Borrowing> search(@Param("kw") String kw);
}
