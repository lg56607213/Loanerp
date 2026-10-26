package com.jdend.erp.accounting.taxinvoice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 세금계산서 발행·수취 내역.
 *
 * <p>매출(내가 발행한 것)과 매입(내가 받은 것)을 한 표에 담고 {@code invoiceType} 으로 가른다.
 * 통장 입출금·전표와 맞대어 세 가지를 찾아내기 위한 것이다 —
 * 발행했는데 못 받은 돈, 받았는데 발행하지 않은 매출, 지급했는데 증빙이 없는 매입.
 */
@Entity
@Table(name = "tax_invoices",
    indexes = {
        @Index(name = "ix_tax_invoice_type_date", columnList = "invoice_type, issue_date"),
        @Index(name = "ix_tax_invoice_partner", columnList = "partner_name")
    })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxInvoice {

  public static final String SALES = "매출";
  public static final String PURCHASE = "매입";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 매출 / 매입 */
  @Column(name = "invoice_type", nullable = false, length = 10)
  private String invoiceType;

  /** 작성일자 */
  @Column(name = "issue_date", nullable = false)
  private LocalDate issueDate;

  /** 전자세금계산서 승인번호. 있으면 같은 건을 두 번 올리는 것을 이것으로 막는다. */
  @Column(name = "approval_no", length = 60)
  private String approvalNo;

  /** 거래처명 — 매출이면 공급받는자, 매입이면 공급자 */
  @Column(name = "partner_name", nullable = false, length = 100)
  private String partnerName;

  @Column(name = "partner_biz_no", length = 20)
  private String partnerBizNo;

  @Column(name = "supply_amount", nullable = false)
  private Long supplyAmount;

  @Column(name = "tax_amount", nullable = false)
  private Long taxAmount;

  /** 합계 = 공급가액 + 세액. 통장 금액과 맞대는 기준이다. */
  @Column(name = "total_amount", nullable = false)
  private Long totalAmount;

  @Column(name = "item", length = 200)
  private String item;

  @Column(name = "remarks", length = 500)
  private String remarks;

  /** 같은 행을 두 번 올리는 것을 막는 지문 */
  @Column(name = "row_hash", length = 64)
  private String rowHash;

  @Column(name = "batch_id", length = 40)
  private String batchId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  public boolean isSales() {
    return SALES.equals(invoiceType);
  }
}
