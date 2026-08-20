package com.jdend.erp.contract.maturitytermination.entity;

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
@Table(name = "maturity_terminations")
public class MaturityTermination {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 계약 스냅샷
  @Column(name="contract_id")
  private Long contractId;

  @Column(name="contract_number", nullable=false, length=30)
  private String contractNumber;

  @Column(name="customer_name", length=100)
  private String customerName;

  @Column(name="contract_type", length=20)
  private String contractType;

  @Column(name="start_date")
  private LocalDate startDate;

  @Column(name="end_date")
  private LocalDate endDate;

  @Column(name="monthly_rent")
  private Long monthlyRent;

  // 종료 정보
  @Column(name="termination_method", nullable=false, length=20)
  private String terminationMethod; // 인수/반납

  @Column(name="termination_date", nullable=false)
  private LocalDate terminationDate;

  @Column(name="unpaid_amount")
  private Long unpaidAmount; // 미청구금액

  @Column(name="status", nullable=false, length=20)
  private String status; // 종료예정/종료완료

  @CreationTimestamp
  @Column(name="created_at", updatable=false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name="updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  public void prePersist() {
    if (unpaidAmount == null) unpaidAmount = 0L;
    if (monthlyRent == null) monthlyRent = 0L;
  }
}