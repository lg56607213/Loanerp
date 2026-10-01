package com.jdend.erp.borrowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 차입금 약정 상환스케줄 한 회차.
 *
 * 차입할 때 약정한 계획일 뿐, 실제로 낸 돈은 borrowing_transactions 에 있다.
 * 둘을 나눠 두는 이유는 약정대로 내지 않는 일이 흔하기 때문이다 —
 * 계획을 실적으로 덮어쓰면 "얼마를 덜 냈는지"를 알 수 없게 된다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(
    name = "borrowing_schedules",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_borrowing_schedule",
        columnNames = {"borrowing_number", "installment_no"})
)
public class BorrowingSchedule {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "borrowing_number", nullable = false, length = 30)
  private String borrowingNumber;

  @Column(name = "installment_no", nullable = false)
  private Integer installmentNo;

  /** 납입예정일 */
  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  /** 약정 상환원금 */
  @Column(name = "principal_amount", nullable = false)
  private Long principalAmount;

  /** 약정 이자 */
  @Column(name = "interest_amount", nullable = false)
  private Long interestAmount;

  /** 이 회차를 낸 뒤 남는 원금 */
  @Column(name = "balance", nullable = false)
  private Long balance;

  /** 약정 납입액 */
  public long total() {
    return nz(principalAmount) + nz(interestAmount);
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
