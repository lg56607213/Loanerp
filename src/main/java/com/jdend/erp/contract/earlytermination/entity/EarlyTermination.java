package com.jdend.erp.contract.earlytermination.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "early_terminations")
public class EarlyTermination {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "contract_id")
  private Long contractId;

  @Column(name = "contract_number")
  private String contractNumber;

  @Column(name = "customer_name")
  private String customerName;

  @Column(name = "contract_type")
  private String contractType;

  @Column(name = "start_date")
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(name = "monthly_rent")
  private Long monthlyRent;

  @Column(name = "total_rent")
  private Long totalRent;

  @Column(name = "termination_method")
  private String terminationMethod; // 인수/반납

  @Column(name = "termination_date")
  private LocalDate terminationDate;

  @Column(name = "status")
  private String status; // 처리대기/처리완료

  @Column(name = "termination_amount")
  private Long terminationAmount;

  @Column(name = "uncollected_rent")
  private Long uncollectedRent;

  @Column(name = "termination_fee")
  private Long terminationFee;

  @Column(name = "company_account")
  private String companyAccount;

  @Column(name = "total_amount")
  private Long totalAmount;

  @CreationTimestamp
  @Column(name="created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name="updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  public void prePersist() {
    if (terminationAmount == null) terminationAmount = 0L;
    if (uncollectedRent == null) uncollectedRent = 0L;
    if (terminationFee == null) terminationFee = 0L;
    if (totalAmount == null) totalAmount = 0L;
  }
}