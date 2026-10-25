package com.jdend.erp.contract.policy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 회사별 여신 정책 — 지금은 이자율 상한 하나다.
 *
 * <p>이 ERP 는 회사마다 DB 가 따로다. 대부업자 장부는 대부업법상 연 20% 를 넘길 수
 * 없지만, 비영업으로 개인이 빌려준 돈을 적는 장부도 같은 ERP 를 쓴다. 상한을
 * 코드에 박아 두면 그런 장부에 실제 약정을 적을 수 없다.
 *
 * <p>그래서 회사 DB 안에 두고 그 회사 책임자가 정한다. 행이 없으면 20% 다 —
 * 지금까지와 같다. 상한을 올리는 것은 법정 상한을 넘기는 기록을 남기겠다는
 * 뜻이므로, 올린 사유를 함께 적게 한다.
 */
@Entity
@Table(name = "loan_policy_settings")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanPolicySettings {

  /** 회사 DB 에 한 행만 둔다. */
  public static final long SINGLETON_ID = 1L;

  @Id
  private Long id;

  /** 약정이율 상한(연 %). */
  @Column(name = "max_interest_rate", nullable = false, precision = 6, scale = 2)
  private BigDecimal maxInterestRate;

  /** 법정 상한을 넘겨 둔 사유. */
  @Column(name = "reason", length = 500)
  private String reason;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = SINGLETON_ID;
  }
}
