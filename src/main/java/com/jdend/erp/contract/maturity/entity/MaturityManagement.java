package com.jdend.erp.contract.maturity.entity;

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
@Table(name = "maturity_managements")
public class MaturityManagement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 기존 계약
  @Column(name = "old_contract_id")
  private Long oldContractId;

  @Column(name = "old_contract_number", nullable = false, length = 30)
  private String oldContractNumber;

  @Column(name = "customer_name", length = 100)
  private String customerName;

  @Column(name = "old_end_date")
  private LocalDate oldEndDate;

  // 신규(재약정)
  @Column(name = "new_contract_number", length = 30)
  private String newContractNumber;

  @Column(name = "new_start_date")
  private LocalDate newStartDate;

  @Column(name = "new_end_date")
  private LocalDate newEndDate;

  @Column(name = "new_monthly_rent")
  private Long newMonthlyRent;

  // 상태: 만기예정 / 재계약완료
  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @CreationTimestamp
  @Column(name="created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name="updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  public void prePersist() {
    if (newMonthlyRent == null) newMonthlyRent = 0L;
  }
}