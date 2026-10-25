package com.jdend.erp.contract.policy;

import com.jdend.erp.contract.support.LoanRateValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** 회사별 여신 정책을 읽고 쓴다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanPolicyService {

  private final LoanPolicyRepository repo;

  /** 이 회사의 약정이율 상한. 정해 둔 것이 없으면 법정 상한(연 20%). */
  @Transactional(readOnly = true)
  public BigDecimal maxInterestRate() {
    return repo.findById(LoanPolicySettings.SINGLETON_ID)
        .map(LoanPolicySettings::getMaxInterestRate)
        .filter(r -> r.compareTo(BigDecimal.ZERO) > 0)
        .orElse(LoanRateValidator.LEGAL_MAX_RATE);
  }

  /** 만기일시의 회차 청구액을 약정 월납입액으로 쓰는가. */
  @Transactional(readOnly = true)
  public boolean useAgreedMonthly() {
    return repo.findById(LoanPolicySettings.SINGLETON_ID)
        .map(LoanPolicySettings::useAgreedMonthly)
        .orElse(false);
  }

  @Transactional(readOnly = true)
  public LoanPolicySettings get() {
    return repo.findById(LoanPolicySettings.SINGLETON_ID)
        .orElse(LoanPolicySettings.builder()
            .id(LoanPolicySettings.SINGLETON_ID)
            .maxInterestRate(LoanRateValidator.LEGAL_MAX_RATE)
            .build());
  }

  /**
   * 상한을 정한다.
   *
   * <p>법정 상한보다 높게 두려면 사유를 받는다. 비영업 개인 대여 장부가 아니라면
   * 올릴 일이 없고, 올린 기록이 남아야 나중에 왜 이렇게 되어 있는지 알 수 있다.
   */
  @Transactional
  public LoanPolicySettings save(BigDecimal maxInterestRate, String reason,
                                Boolean useAgreedMonthly) {
    if (maxInterestRate == null || maxInterestRate.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("이자율 상한은 0% 보다 커야 합니다.");
    }
    boolean overLegal = maxInterestRate.compareTo(LoanRateValidator.LEGAL_MAX_RATE) > 0;
    if (overLegal && (reason == null || reason.isBlank())) {
      throw new IllegalArgumentException(
          "법정 최고이자율(연 " + LoanRateValidator.LEGAL_MAX_RATE.stripTrailingZeros().toPlainString()
          + "%)보다 높게 두려면 사유를 적어야 합니다. 대부업 장부에는 올릴 수 없습니다.");
    }

    LoanPolicySettings s = repo.findById(LoanPolicySettings.SINGLETON_ID)
        .orElseGet(() -> LoanPolicySettings.builder().id(LoanPolicySettings.SINGLETON_ID).build());
    s.setMaxInterestRate(maxInterestRate);
    s.setReason(reason == null ? null : reason.trim());
    if (useAgreedMonthly != null) s.setUseAgreedMonthlyYn(useAgreedMonthly);
    LoanPolicySettings saved = repo.save(s);
    if (overLegal) {
      log.warn("[LoanPolicy] 이자율 상한을 연 {}% 로 둔다 — {}", maxInterestRate, saved.getReason());
    }
    return saved;
  }
}
