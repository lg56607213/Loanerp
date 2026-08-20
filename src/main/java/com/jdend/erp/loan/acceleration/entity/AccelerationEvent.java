package com.jdend.erp.loan.acceleration.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 기한이익상실(EOD).
 *
 * 등록하면 채권 상태가 '해지'가 되고, 그 시점부터 잔여원금 전액이 즉시 청구 대상이 된다.
 * 미래 회차는 개별 청구를 멈추고(청구중지) 잔여원금을 일괄 채권으로 본다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(name = "acceleration_events")
public class AccelerationEvent {

  public static final String REASON_OVERDUE   = "연체";
  public static final String REASON_BANKRUPT  = "파산·회생";
  public static final String REASON_CREDIT    = "신용악화";
  public static final String REASON_BREACH    = "약정위반";
  public static final String REASON_ETC       = "기타";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "contract_id")
  private Long contractId;

  @Column(name = "contract_number", nullable = false, length = 30)
  private String contractNumber;

  @Column(name = "customer_name", length = 100)
  private String customerName;

  /** 기한이익상실일 */
  @Column(name = "eod_date", nullable = false)
  private LocalDate eodDate;

  /** 사전통지일 — 대부업법상 통지 의무 */
  @Column(name = "notice_date")
  private LocalDate noticeDate;

  @Column(name = "reason", length = 20)
  private String reason;

  /** 상실일 기준 잔여원금 전액 */
  @Column(name = "called_principal")
  private Long calledPrincipal;

  /** 상실일까지 미수이자 (일할 정산) */
  @Column(name = "accrued_interest")
  private Long accruedInterest;

  /** 상실일까지 지연배상금 */
  @Column(name = "accrued_overdue")
  private Long accruedOverdue;

  /** 청구 총액 */
  @Column(name = "total_called")
  private Long totalCalled;

  /** 청구중지로 전환한 미래 회차 수 */
  @Column(name = "suspended_installments")
  private Integer suspendedInstallments;

  @Column(name = "memo", columnDefinition = "TEXT")
  private String memo;

  @Column(name = "voucher_id")
  private Long voucherId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  public void prePersist() {
    if (calledPrincipal == null) calledPrincipal = 0L;
    if (accruedInterest == null) accruedInterest = 0L;
    if (accruedOverdue == null) accruedOverdue = 0L;
    if (totalCalled == null) totalCalled = calledPrincipal + accruedInterest + accruedOverdue;
    if (suspendedInstallments == null) suspendedInstallments = 0;
  }
}
