package com.jdend.erp.accounting.partnerbalance.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 거래처 미수금·미지급금.
 *
 * <p>대출 미수금(receivables)은 대출계약에 묶여 있어 대부업이 아닌 회사에서는 쓸 수 없고,
 * 미지급관리는 전표 대변 라인을 그대로 보여 주어 이미 지급한 건까지 함께 뜬다.
 * 거래처 단위로 '얼마가 남았는지' 를 들고 회수·지급을 쌓아 가는 자리가 필요해서 둔다.
 *
 * <p>발생금액에서 회수(지급) 누계를 뺀 것이 잔액이고, 잔액이 0 이면 완료다.
 */
@Entity
@Table(name = "partner_balances",
    indexes = {
        @Index(name = "ix_partner_balance_type", columnList = "balance_type, occurred_date"),
        @Index(name = "ix_partner_balance_name", columnList = "partner_name")
    })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartnerBalance {

  /** 받을 돈 */
  public static final String RECEIVABLE = "미수금";
  /** 줄 돈 */
  public static final String PAYABLE = "미지급금";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "balance_type", nullable = false, length = 10)
  private String balanceType;

  @Column(name = "partner_name", nullable = false, length = 100)
  private String partnerName;

  @Column(name = "partner_biz_no", length = 20)
  private String partnerBizNo;

  /** 발생일 — 세금계산서 작성일이나 거래일 */
  @Column(name = "occurred_date", nullable = false)
  private LocalDate occurredDate;

  /** 받기로(주기로) 한 날 */
  @Column(name = "due_date")
  private LocalDate dueDate;

  @Column(name = "amount", nullable = false)
  private Long amount;

  /** 그동안 받은(준) 누계 */
  @Column(name = "settled_amount", nullable = false)
  private Long settledAmount;

  /** 근거 — 세금계산서 승인번호, 계약번호 등 */
  @Column(name = "source", length = 100)
  private String source;

  @Column(name = "item", length = 200)
  private String item;

  @Column(name = "remarks", length = 500)
  private String remarks;

  @Column(name = "row_hash", length = 64)
  private String rowHash;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  public void prePersist() {
    if (settledAmount == null) settledAmount = 0L;
  }

  public long balance() {
    return nz(amount) - nz(settledAmount);
  }

  /** 미결 / 부분 / 완료 */
  public String status() {
    long left = balance();
    if (left == 0) return "완료";
    return nz(settledAmount) == 0 ? "미결" : "부분";
  }

  private static long nz(Long v) { return v == null ? 0L : v; }
}
