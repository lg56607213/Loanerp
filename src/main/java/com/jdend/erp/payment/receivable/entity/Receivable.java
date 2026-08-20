package com.jdend.erp.payment.receivable.entity;

import com.jdend.erp.contract.entity.Contract;
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
@Table(name = "receivables")
public class Receivable {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 계약 FK (있으면 연결, 계약 삭제 시 null)
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "contract_id")
  private Contract contract;

  @Column(name = "contract_number", nullable = false, length = 30)
  private String contractNumber;

  @Column(name = "customer_name", length = 100)
  private String customerName;

  @Column(name = "receivable_amount", nullable = false)
  private Long receivableAmount;

  @Column(name = "receivable_date", nullable = false)
  private LocalDate receivableDate;

  @Column(name = "receivable_type", length = 20)
  private String receivableType;

  @Column(name = "content", columnDefinition = "TEXT", nullable = false)
  private String content;

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
    if (status == null || status.isBlank()) status = "미납";
    if (receivableAmount == null) receivableAmount = 0L;
  }
}