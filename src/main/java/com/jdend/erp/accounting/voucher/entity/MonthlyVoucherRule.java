package com.jdend.erp.accounting.voucher.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_voucher_rules")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyVoucherRule {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @Column(name = "contract_number", length = 50)
  private String contractNumber;

  @Column(name = "monthly_day", nullable = false)
  private Integer monthlyDay;

  @Column(name = "next_run_date", nullable = false)
  private LocalDate nextRunDate;

  @Column(name = "last_run_date")
  private LocalDate lastRunDate;

  @Column(name = "debit_account_code", length = 30)
  private String debitAccountCode;

  @Column(name = "debit_account", nullable = false, length = 100)
  private String debitAccount;

  @Column(name = "debit_amount", nullable = false)
  private Long debitAmount;

  @Column(name = "debit_description", length = 255)
  private String debitDescription;

  @Column(name = "credit_account_code", length = 30)
  private String creditAccountCode;

  @Column(name = "credit_account", nullable = false, length = 100)
  private String creditAccount;

  @Column(name = "credit_amount", nullable = false)
  private Long creditAmount;

  @Column(name = "credit_description", length = 255)
  private String creditDescription;

  @Column(name = "memo", length = 255)
  private String memo;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;
}