package com.jdend.erp.loan.writeoff.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 대손상각.
 *
 * 등록하면 채권 상태가 '상각'이 되고 이후 이자·지연배상금 기산이 멈춘다.
 * 상각 후 회수가 발생하면 상각채권추심이익으로 인식하며, 이때 변제충당 순서는
 * 법적비용 → 원금 → 이자로 바뀐다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(name = "write_offs")
public class WriteOff {

  public static final String REASON_PRESCRIPTION = "소멸시효완성";
  public static final String REASON_DISCHARGE    = "파산면책";
  public static final String REASON_UNCOLLECTIBLE = "회수불능";
  public static final String REASON_SOLD         = "채권매각";
  public static final String REASON_ETC          = "기타";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "contract_id")
  private Long contractId;

  @Column(name = "contract_number", nullable = false, length = 30)
  private String contractNumber;

  @Column(name = "customer_name", length = 100)
  private String customerName;

  @Column(name = "write_off_date", nullable = false)
  private LocalDate writeOffDate;

  @Column(name = "reason", length = 20)
  private String reason;

  @Column(name = "write_off_principal")
  private Long writeOffPrincipal;

  @Column(name = "write_off_interest")
  private Long writeOffInterest;

  @Column(name = "write_off_overdue")
  private Long writeOffOverdue;

  /** 대손충당금 상계액 */
  @Column(name = "allowance_used")
  private Long allowanceUsed;

  /** 대손상각비 계상액 */
  @Column(name = "expense_amount")
  private Long expenseAmount;

  @Column(name = "total_written_off")
  private Long totalWrittenOff;

  /** 상각 직전 채권 상태 — 상각 취소 시 되돌린다 */
  @Column(name = "prev_status", length = 20)
  private String prevStatus;

  @Column(name = "memo", columnDefinition = "TEXT")
  private String memo;

  @Column(name = "voucher_id")
  private Long voucherId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  public void prePersist() {
    if (writeOffPrincipal == null) writeOffPrincipal = 0L;
    if (writeOffInterest == null) writeOffInterest = 0L;
    if (writeOffOverdue == null) writeOffOverdue = 0L;
    if (allowanceUsed == null) allowanceUsed = 0L;
    if (totalWrittenOff == null) totalWrittenOff = writeOffPrincipal + writeOffInterest + writeOffOverdue;
    if (expenseAmount == null) expenseAmount = Math.max(0L, totalWrittenOff - allowanceUsed);
  }
}
