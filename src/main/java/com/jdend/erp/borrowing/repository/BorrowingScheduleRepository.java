package com.jdend.erp.borrowing.repository;

import com.jdend.erp.borrowing.entity.BorrowingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BorrowingScheduleRepository extends JpaRepository<BorrowingSchedule, Long> {

  List<BorrowingSchedule> findByBorrowingNumberOrderByInstallmentNoAsc(String borrowingNumber);

  void deleteByBorrowingNumber(String borrowingNumber);
}
