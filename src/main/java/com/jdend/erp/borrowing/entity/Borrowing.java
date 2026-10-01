package com.jdend.erp.borrowing.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 차입금 — 회사가 빌려온 돈.
 *
 * 대출(contracts)이 고객에게 빌려준 돈이라면, 차입금은 그 재원으로 빌려온 돈이다.
 * 방향이 반대라 부채 계정(차입금)에 잡히고, 이자는 수익이 아니라 비용(이자비용)이다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(name = "borrowings")
public class Borrowing {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 차입번호 — B + yyMMdd + 일련번호 3자리 */
  @Column(name = "borrowing_number", nullable = false, unique = true, length = 30)
  private String borrowingNumber;

  /** 차입처 — 은행·개인·법인 이름 */
  @Column(name = "lender_name", nullable = false, length = 100)
  private String lenderName;

  /** 차입구분 — 금융기관 / 개인 / 법인 / 관계사 */
  @Column(name = "borrowing_type", length = 30)
  private String borrowingType;

  /** 차입처 연락처 */
  @Column(name = "lender_contact", length = 50)
  private String lenderContact;

  /** 차입원금 */
  @Column(name = "principal_amount", nullable = false)
  private Long principalAmount;

  /** 차입일 — 돈이 들어온 날. 이자 기산일이기도 하다. */
  @Column(name = "borrow_date", nullable = false)
  private LocalDate borrowDate;

  /** 만기일 */
  @Column(name = "maturity_date")
  private LocalDate maturityDate;

  /** 약정 연이율(%) */
  @Column(name = "interest_rate", precision = 7, scale = 3)
  private BigDecimal interestRate;

  /** 상환방식 — 만기일시 / 원금균등 / 원리금균등 / 수시상환 */
  @Column(name = "repayment_method", length = 30)
  private String repaymentMethod;

  /** 분할상환 회차수. 만기일시·수시상환이면 쓰지 않는다. */
  @Column(name = "installment_count")
  private Integer installmentCount;

  /** 납입일자(1~31). 납입일구분이 '말일'이면 쓰지 않는다. */
  @Column(name = "payment_day")
  private Integer paymentDay;

  /** 납입일구분 — 일자 / 말일 */
  @Column(name = "payment_day_type", length = 10)
  private String paymentDayType;

  /** 입금받은 당사 계좌 — 차입 전표의 차변 적요에 쓴다. */
  @Column(name = "deposit_account", length = 100)
  private String depositAccount;

  /** 상태 — 정상 / 완제 */
  @Column(name = "status", nullable = false, length = 20)
  private String status;

  /**
   * 미상환 원금.
   *
   * 등록한 상환 내역을 다시 합산해 채우는 값이다(BorrowingBalanceService).
   * 화면마다 매번 더하지 않으려고 들고 있는 것이지 별도의 진실이 아니다.
   */
  @Column(name = "remaining_principal", nullable = false)
  private Long remainingPrincipal;

  /** 차입 개시 전표 id */
  @Column(name = "voucher_id")
  private Long voucherId;

  @Column(name = "remarks", length = 500)
  private String remarks;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;
}
