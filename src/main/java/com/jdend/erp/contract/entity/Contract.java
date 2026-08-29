package com.jdend.erp.contract.entity;

import com.jdend.erp.customer.Customer;
import com.jdend.erp.contract.support.DebtTypeCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 여신계약(대출채권).
 *
 * 렌터카 ERP의 렌트계약을 대부업 여신계약으로 전환한 엔티티다.
 * 테이블명(contracts)은 수납·청구·전표·미수·법적절차가 모두 contract_number로 물려 있어 그대로 둔다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(name = "contracts")
public class Contract {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 채권번호 */
  @Column(name="contract_number", nullable=false, unique=true, length=30)
  private String contractNumber;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name="customer_id")
  private Customer customer;

  @Column(name="customer_number", nullable=false, length=30)
  private String customerNumber;

  /** 개인 / 법인 — 금소법상 연체이자 부과 제한 판단에 사용 */
  @Column(name="customer_type", length=10)
  private String customerType;

  /** 신용대출 / 담보대출 / 사업자대출 */
  @Column(name="loan_type", length=20)
  private String loanType;

  /**
   * 개인금융채권 / 기타 — 개인채무자보호법 적용 판정에 쓴다.
   *
   * 최초원금 5,000만원 미만 + 개인 + 개인금융채권이면 기한이익상실 이후에도
   * 원래 납기일이 도래하지 않은 원금에는 연체가산이자를 붙일 수 없다.
   * 나머지 두 조건은 loanAmount·customerType 으로 판정되고 이 값만 없었다.
   */
  @Column(name="debt_type", length=20)
  private String debtType;

  // ── 여신 조건 ───────────────────────────────────────────────

  /** 대출금(원금) */
  @Column(name="loan_amount", nullable=false)
  private Long loanAmount;

  /** 실행일 */
  @Column(name="execute_date")
  private LocalDate executeDate;

  /** 약정 연이율(%) — 대부업법상 20% 초과 불가 */
  @Column(name="interest_rate", precision=5, scale=2)
  private BigDecimal interestRate;

  /** 연체이율(%) — min(약정이율 + 3, 20) 초과 불가 */
  @Column(name="overdue_rate", precision=5, scale=2)
  private BigDecimal overdueRate;

  /**
   * 연체이자 부과 여부.
   * 금소법상 개인 3천만원 이하 등 미부과 대상 채권은 false로 두고 지연배상금을 산정하지 않는다.
   */
  @Column(name="overdue_charge_yn", nullable=false)
  private Boolean overdueChargeYn;

  /** 원리금균등 / 원금균등 / 만기일시 */
  @Column(name="repayment_method", nullable=false, length=20)
  private String repaymentMethod;

  @Column(name="start_date", nullable=false)
  private LocalDate startDate;

  @Column(name="end_date", nullable=false)
  private LocalDate endDate;

  /** 납입일자 (1~31, 말일 클램핑) */
  @Column(name="payment_day")
  private Integer paymentDay;

  /** 총 회차수 */
  @Column(name="installment_count", nullable=false)
  private Integer installmentCount;

  /** 월납입액 — 원리금균등은 PMT 공식으로 자동 산출 */
  @Column(name="monthly_payment", nullable=false)
  private Long monthlyPayment;

  // ── 채권 상태 ───────────────────────────────────────────────

  /**
   * 정상 / 연체 / 해지 / 상각 / 종료.
   * 해지·상각·종료만 이벤트 시점에 저장하고, 정상·연체는 미납 스케줄로 조회 시점에 파생 판정한다.
   */
  @Column(name="status", nullable=false, length=20)
  private String status;

  /** 잔여원금 — 수납 충당 시 갱신 */
  @Column(name="remaining_principal")
  private Long remainingPrincipal;

  @Column(name="remarks", columnDefinition = "TEXT")
  private String remarks;

  @CreationTimestamp
  @Column(name="created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name="updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  public void prePersist() {
    if (status == null || status.isBlank()) status = ContractStatus.NORMAL;
    if (overdueChargeYn == null) overdueChargeYn = Boolean.TRUE;
    if (repaymentMethod == null || repaymentMethod.isBlank()) repaymentMethod = RepaymentMethod.EQUAL_PAYMENT;
    if (loanAmount == null) loanAmount = 0L;
    if (debtType == null || debtType.isBlank()) debtType = DebtTypeCode.defaultFor(customerType, loanAmount);
    if (monthlyPayment == null) monthlyPayment = 0L;
    if (installmentCount == null) installmentCount = 0;
    if (remainingPrincipal == null) remainingPrincipal = loanAmount;
  }
}
