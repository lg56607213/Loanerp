package com.jdend.erp.payment.billing.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "contracts")
public class Contracts {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "contract_number")
  private String contractNumber;

  @Column(name = "customer_id")
  private Long customerId;

  @Column(name = "customer_number")
  private String customerNumber;

  @Column(name = "monthly_payment")
  private Long monthlyPayment;
}