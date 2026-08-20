package com.jdend.erp.payment.schedule.entity;

import com.jdend.erp.contract.entity.Contract;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 상환스케줄 한 회차.
 *
 * 회차 금액은 월할로 산출하고(원금 + 이자), 수납이 들어오면 변제충당 결과를
 * paidPrincipal / paidInterest / paidOverdueInterest / paidCost 에 나눠 기록한다.
 * 이렇게 회차별로 충당 실적을 남겨야 부분납 상태와 미납 잔액을 정확히 추적할 수 있다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(name = "payment_schedules",
    uniqueConstraints = @UniqueConstraint(name="uk_payment_schedules_contract_installment",
        columnNames = {"contract_number", "installment_no"})
)
public class PaymentSchedule {

  /** 회차 상태 */
  public static final String LINE_UNPAID   = "미납";
  public static final String LINE_PARTIAL  = "부분납";
  public static final String LINE_PAID     = "완납";
  /** 기한이익상실로 잔여원금을 일괄 청구해 개별 회차 청구를 멈춘 상태 */
  public static final String LINE_SUSPENDED = "청구중지";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name="contract_id")
  private Contract contract;

  @Column(name = "contract_id", insertable = false, updatable = false)
  private Long contractId;

  @Column(name="contract_number", nullable=false, length=30)
  private String contractNumber;

  @Column(name="installment_no", nullable=false)
  private Integer installmentNo;

  /** 회차 기간 시작 */
  @Column(name="bill_start_date")
  private LocalDate billStartDate;

  /** 회차 기간 종료 */
  @Column(name="bill_end_date")
  private LocalDate billEndDate;

  /** 납기일 — 청구·미수 집계 기준 */
  @Column(name="tax_invoice_date")
  private LocalDate taxInvoiceDate;

  /** 납입예정일 — 연체는 이 날짜 다음날(D+1)부터 기산한다 */
  @Column(name="payment_date")
  private LocalDate paymentDate;

  /** 회차 청구액 = 원금 + 이자 */
  @Column(name="rent_amount")
  private Long rentAmount;

  @Column(name="principal_amount")
  private Long principalAmount;

  @Column(name="interest_amount")
  private Long interestAmount;

  /** 이 회차를 납입한 뒤의 잔여원금 */
  @Column(name="remaining_principal")
  private Long remainingPrincipal;

  // ── 충당 실적 ────────────────────────────────────────────

  @Column(name="paid_principal")
  private Long paidPrincipal;

  @Column(name="paid_interest")
  private Long paidInterest;

  /** 지연배상금 충당액 */
  @Column(name="paid_overdue_interest")
  private Long paidOverdueInterest;

  /** 법적비용 충당액 */
  @Column(name="paid_cost")
  private Long paidCost;

  /** 미납 / 부분납 / 완납 / 청구중지 */
  @Column(name="line_status", length=10)
  private String lineStatus;

  @CreationTimestamp
  @Column(name="created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name="updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  public void prePersist() {
    if (paidPrincipal == null) paidPrincipal = 0L;
    if (paidInterest == null) paidInterest = 0L;
    if (paidOverdueInterest == null) paidOverdueInterest = 0L;
    if (paidCost == null) paidCost = 0L;
    if (lineStatus == null || lineStatus.isBlank()) lineStatus = LINE_UNPAID;
  }

  // ── 파생 계산 ────────────────────────────────────────────

  public long dueTotal() {
    return nz(principalAmount) + nz(interestAmount);
  }

  public long paidTotal() {
    return nz(paidPrincipal) + nz(paidInterest);
  }

  /** 이 회차의 미납 잔액 (원금 + 이자 기준) */
  public long unpaidTotal() {
    long v = dueTotal() - paidTotal();
    return v > 0 ? v : 0L;
  }

  public long unpaidPrincipal() {
    long v = nz(principalAmount) - nz(paidPrincipal);
    return v > 0 ? v : 0L;
  }

  public long unpaidInterest() {
    long v = nz(interestAmount) - nz(paidInterest);
    return v > 0 ? v : 0L;
  }

  /** 충당 결과에 맞춰 회차 상태를 다시 계산한다. 청구중지는 유지한다. */
  public void refreshLineStatus() {
    if (LINE_SUSPENDED.equals(lineStatus)) return;
    long due = dueTotal();
    long paid = paidTotal();
    if (due > 0 && paid >= due) lineStatus = LINE_PAID;
    else if (paid > 0)          lineStatus = LINE_PARTIAL;
    else                        lineStatus = LINE_UNPAID;
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
