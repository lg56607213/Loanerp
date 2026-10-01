package com.jdend.erp.borrowing.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 차입금 상환·이자지급 한 건.
 *
 * 원금과 이자를 한 건에 같이 담는다. 실무에서 한 번 이체하며 원금과 이자를
 * 같이 보내는 일이 흔한데, 그걸 두 건으로 쪼개면 통장 내역과 줄 수가 어긋난다.
 * 이자만 낸 달은 principalAmount 가 0 이고, 원금만 갚은 날은 interestAmount 가 0 이다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(name = "borrowing_transactions")
public class BorrowingTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "borrowing_number", nullable = false, length = 30)
  private String borrowingNumber;

  /** 지급일 — 실제로 돈이 나간 날 */
  @Column(name = "tx_date", nullable = false)
  private LocalDate txDate;

  /** 상환원금 */
  @Column(name = "principal_amount", nullable = false)
  private Long principalAmount;

  /** 지급이자 */
  @Column(name = "interest_amount", nullable = false)
  private Long interestAmount;

  /**
   * 이자 계산 기산일 — 이 지급이 어느 기간의 이자인지.
   *
   * 자동계산한 금액을 사용자가 고칠 수 있으므로, 어느 기간을 기준으로
   * 계산했는지 남겨두지 않으면 나중에 금액이 맞는지 확인할 수 없다.
   */
  @Column(name = "interest_from")
  private LocalDate interestFrom;

  /** 이자 계산 종료일 */
  @Column(name = "interest_to")
  private LocalDate interestTo;

  /** 출금한 당사 계좌 */
  @Column(name = "withdraw_account", length = 100)
  private String withdrawAccount;

  /** 상환스케줄 회차. 수시상환이면 null. */
  @Column(name = "installment_no")
  private Integer installmentNo;

  @Column(name = "memo", length = 255)
  private String memo;

  /** 상환 전표 id */
  @Column(name = "voucher_id")
  private Long voucherId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  /** 이 건의 총 지급액 */
  public long total() {
    return nz(principalAmount) + nz(interestAmount);
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
