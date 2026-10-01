package com.jdend.erp.borrowing.repository;

import com.jdend.erp.borrowing.entity.BorrowingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BorrowingTransactionRepository extends JpaRepository<BorrowingTransaction, Long> {

  /** 잔액을 다시 계산할 때는 날짜순으로 흘려보내야 하므로 정렬해서 꺼낸다. */
  List<BorrowingTransaction> findByBorrowingNumberOrderByTxDateAscIdAsc(String borrowingNumber);

  List<BorrowingTransaction> findByBorrowingNumberOrderByTxDateDescIdDesc(String borrowingNumber);

  boolean existsByBorrowingNumber(String borrowingNumber);

  void deleteByBorrowingNumber(String borrowingNumber);
}
