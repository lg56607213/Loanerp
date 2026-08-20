package com.jdend.erp.contract.entity;

import com.jdend.erp.customer.Customer;
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
@Table(name = "contracts")
public class Contract {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name="contract_number", nullable=false, unique=true, length=30)
  private String contractNumber;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name="customer_id")
  private Customer customer;

  @Column(name="customer_number", nullable=false, length=30)
  private String customerNumber;

  @Column(name="vehicle_no", nullable=false, length=30)
  private String vehicleNo;

  @Column(name="vehicle_model", length=50)
  private String vehicleModel;

  @Column(name="contract_type", nullable=false, length=20)
  private String contractType;

  @Column(name="contract_category", nullable=false, length=20)
  private String contractCategory;

  // ✅✅ DB에 실제로 추가한 상태 컬럼
  @Column(name="status", nullable=false, length=20)
  private String status;

  @Column(name="start_date", nullable=false)
  private LocalDate startDate;

  @Column(name="end_date", nullable=false)
  private LocalDate endDate;

  @Column(name="tax_invoice_day")
  private Integer taxInvoiceDay;

  @Column(name="payment_due_day", length=20)
  private String paymentDueDay;

  @Column(name="advance_payment", nullable=false)
  private Long advancePayment;

  @Column(name="monthly_rent", nullable=false)
  private Long monthlyRent;

  @Column(name="billing_day")
  private Integer billingDay;

  @Column(name="billing_count", nullable=false)
  private Integer billingCount;

  @Column(name="total_rent", nullable=false)
  private Long totalRent;

  @Column(name="deposit", nullable=false)
  private Long deposit;

  @Column(name="maturity_option", length=30)
  private String maturityOption;

  @Column(name="residual_value", nullable=false)
  private Long residualValue;

  @Column(name="vehicle_insurance", length=10)
  private String vehicleInsurance;

  @Column(name="insurance_age", length=20)
  private String insuranceAge;

  @Column(name="vehicle_insurance_limit", length=50)
  private String vehicleInsuranceLimit;

  @Column(name="vehicle_deductible", length=50)
  private String vehicleDeductible;

  @Column(name="property_liability", length=50)
  private String propertyLiability;

  @Column(name="property_deductible", length=50)
  private String propertyDeductible;

  @Column(name="personal_deductible", length=50)
  private String personalDeductible;

  @Column(name="passenger_deductible", length=50)
  private String passengerDeductible;

  @Column(name="remarks", columnDefinition = "TEXT")
  private String remarks;

  // BUG-04 수정: insertable=false 제거 → Hibernate가 INSERT/UPDATE 시점에 자동 설정
  @CreationTimestamp
  @Column(name="created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name="updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  public void prePersist() {
    // ✅ status 기본값
    if (status == null || status.isBlank()) status = "진행중";

    if (advancePayment == null) advancePayment = 0L;
    if (monthlyRent == null) monthlyRent = 0L;
    if (billingCount == null) billingCount = 0;
    if (totalRent == null) totalRent = 0L;
    if (deposit == null) deposit = 0L;
    if (residualValue == null) residualValue = 0L;
  }
}