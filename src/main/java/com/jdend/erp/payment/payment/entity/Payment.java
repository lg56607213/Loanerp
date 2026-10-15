package com.jdend.erp.payment.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(name = "payments")
public class Payment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name="contract_id", nullable=false)
  private Long contractId;

  @Column(name="contract_number", nullable=false, length=30)
  private String contractNumber;

  @Column(name="customer_id")
  private Long customerId;

  @Column(name="customer_number", length=30)
  private String customerNumber;

  @Column(name="customer_name", length=100)
  private String customerName;

  @Column(name="payment_date", nullable=false)
  private LocalDate paymentDate;

  @Column(name="payment_amount", nullable=false)
  private Long paymentAmount;

  @Column(name="payment_method", length=30)
  private String paymentMethod;

  @Column(name="company_account", length=30)
  private String companyAccount;

  @Column(name="memo", length=255)
  private String memo;

  /**
   * 충당기준일 — 이 수납을 며칠자로 쳐서 충당할지.
   *
   * 납입일 전에 미리 보내오는 일이 흔하다. 25일이 납입일인데 20일에 받으면
   * 받은 날로 계산한 경과이자는 닷새치가 모자라 약정한 월 납입액과 맞지 않는다.
   * 그래서 '언제 받았는가'(payment_date)와 '며칠치로 치는가'를 나눠 둔다.
   *
   * 비어 있으면 payment_date 를 쓴다. 기존 수납은 모두 여기에 해당한다.
   */
  @Column(name="apply_date")
  private LocalDate applyDate;

  /** 도래 회차를 다 메우고 남은 돈 처리 — 원금충당 / 선수금. 비면 원금충당. */
  @Column(name="surplus_policy", length=20)
  private String surplusPolicy;

  /** 수납 재원 — 보통예금 / 선수금. 비면 보통예금. */
  @Column(name="payment_source", length=20)
  private String paymentSource;

  /** BUG-03: 수납 등록 시 생성된 전표 ID. 삭제·수정 시 연동 전표 처리에 사용. */
  @Column(name="voucher_id")
  private Long voucherId;

  /**
   * 지정 충당 — 원금.
   *
   * 지난 상환내역을 엑셀로 옮겨 적을 때 쓴다. 그때의 원금·이자 나눔은 이미
   * 끝난 일이고 전표까지 그 금액으로 끊겨 있으므로, 다시 계산하면 장부와
   * 어긋난다. 값이 있으면 충당을 계산하지 않고 이 금액 그대로 반영한다.
   *
   * ERP 에서 평소에 하는 수납은 비워 둔다 — 그건 변제충당 순서대로 계산한다.
   */
  @Column(name="fixed_principal")
  private Long fixedPrincipal;

  /** 지정 충당 — 이자 */
  @Column(name="fixed_interest")
  private Long fixedInterest;

  /** 파일에 적혀 있던 상환 후 대출잔액. 대조용으로만 들고 있는다. */
  @Column(name="fixed_balance")
  private Long fixedBalance;

  /** 원금·이자를 지정해 올린 건인가 */
  public boolean hasFixedAllocation() {
    return fixedPrincipal != null || fixedInterest != null;
  }

  @CreationTimestamp
  @Column(name="created_at", updatable=false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name="updated_at")
  private LocalDateTime updatedAt;
}