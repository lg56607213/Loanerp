package com.jdend.erp.loan.support;

import com.jdend.erp.contract.entity.Contract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단기/장기대여금 판정.
 *
 * 규칙: 대출기간 1년(365일) 미만이면 단기대여금(100301), 1년 이상이면 장기대여금(100302).
 * 실행·회수·상각이 모두 같은 계정을 써야 계정별 잔액이 어긋나지 않으므로 여기서 고정한다.
 */
class LoanReceivableAccountTest {

  private Contract loan(LocalDate execute, LocalDate end, Integer installments) {
    return Contract.builder()
        .contractNumber("L-TEST-0001")
        .customerNumber("C-0001")
        .executeDate(execute)
        .startDate(execute)
        .endDate(end)
        .installmentCount(installments)
        .build();
  }

  @Test
  @DisplayName("만기가 1년에서 하루 모자라면 단기대여금")
  void justUnderOneYearIsShortTerm() {
    Contract c = loan(LocalDate.of(2026, 1, 10), LocalDate.of(2027, 1, 9), 12);
    assertThat(LoanReceivableAccount.isShortTerm(c)).isTrue();
    assertThat(LoanReceivableAccount.codeOf(c)).isEqualTo("100301");
    assertThat(LoanReceivableAccount.nameOf(c)).isEqualTo("단기대여금");
  }

  @Test
  @DisplayName("만기가 정확히 1년이면 장기대여금")
  void exactlyOneYearIsLongTerm() {
    Contract c = loan(LocalDate.of(2026, 1, 10), LocalDate.of(2027, 1, 10), 12);
    assertThat(LoanReceivableAccount.isShortTerm(c)).isFalse();
    assertThat(LoanReceivableAccount.codeOf(c)).isEqualTo("100302");
    assertThat(LoanReceivableAccount.nameOf(c)).isEqualTo("장기대여금");
  }

  @Test
  @DisplayName("6개월 대출은 단기대여금")
  void sixMonthLoanIsShortTerm() {
    Contract c = loan(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 1), 6);
    assertThat(LoanReceivableAccount.codeOf(c)).isEqualTo("100301");
  }

  @Test
  @DisplayName("3년 대출은 장기대여금")
  void threeYearLoanIsLongTerm() {
    Contract c = loan(LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1), 36);
    assertThat(LoanReceivableAccount.codeOf(c)).isEqualTo("100302");
  }

  @Test
  @DisplayName("대손충당금 계정은 대여금과 짝이 맞는다")
  void allowanceMatchesReceivable() {
    Contract shortLoan = loan(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 7, 10), 6);
    assertThat(LoanReceivableAccount.allowanceCodeOf(shortLoan)).isEqualTo("10030101");
    assertThat(LoanReceivableAccount.allowanceNameOf(shortLoan)).isEqualTo("단기대여금 대손충당금");

    Contract longLoan = loan(LocalDate.of(2026, 1, 10), LocalDate.of(2029, 1, 10), 36);
    assertThat(LoanReceivableAccount.allowanceCodeOf(longLoan)).isEqualTo("10030201");
    assertThat(LoanReceivableAccount.allowanceNameOf(longLoan)).isEqualTo("장기대여금 대손충당금");
  }

  @Test
  @DisplayName("종료일이 없으면 회차수로 판정한다 (월납 12회 = 1년)")
  void fallsBackToInstallmentCount() {
    assertThat(LoanReceivableAccount.codeOf(loan(LocalDate.of(2026, 1, 10), null, 6)))
        .isEqualTo("100301");
    assertThat(LoanReceivableAccount.codeOf(loan(LocalDate.of(2026, 1, 10), null, 12)))
        .isEqualTo("100302");
    assertThat(LoanReceivableAccount.codeOf(loan(LocalDate.of(2026, 1, 10), null, 24)))
        .isEqualTo("100302");
  }

  @Test
  @DisplayName("판단 근거가 전혀 없으면 장기로 본다")
  void defaultsToLongTermWhenUnknown() {
    assertThat(LoanReceivableAccount.codeOf(loan(null, null, null))).isEqualTo("100302");
    assertThat(LoanReceivableAccount.codeOf(null)).isEqualTo("100302");
  }

  @Test
  @DisplayName("종료일이 시작일보다 앞서는 이상 데이터는 회차수로 넘어간다")
  void invalidDateRangeFallsBack() {
    Contract c = loan(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), 6);
    assertThat(LoanReceivableAccount.codeOf(c)).isEqualTo("100301");
  }
}
