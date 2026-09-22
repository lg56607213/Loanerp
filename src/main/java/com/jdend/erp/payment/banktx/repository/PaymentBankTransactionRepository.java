package com.jdend.erp.payment.banktx.repository;

import com.jdend.erp.payment.banktx.entity.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PaymentBankTransactionRepository extends JpaRepository<BankTransaction, Long> {

  boolean existsByRowHash(String rowHash);

  /**
   * 같은 거래가 이미 있는지 필드로 확인한다.
   *
   * 예전에는 row_hash 로만 판정했는데 그 해시에 잔액이 빠져 있었다. 그래서
   * 같은 날 같은 금액을 같은 상대와 두 번 주고받은 서로 다른 거래가 중복으로
   * 묶여 조용히 사라졌다(실제로 같은 날 1천만원을 두 번 입금한 건이 누락됐다).
   * 잔액은 거래마다 달라지므로 판정에 넣어야 한다.
   *
   * 해시 대신 필드로 보는 이유는 이미 저장된 행들이 옛 방식 해시를 갖고 있어서다.
   * 필드로 보면 기존 데이터를 건드리지 않고도 바로 정확해진다.
   */
  @Query("""
    select count(t) > 0
    from BankTransaction t
    where t.bankName = :bankName
      and t.accountNo = :accountNo
      and t.txDate = :txDate
      and t.depositAmount = :deposit
      and t.withdrawalAmount = :withdrawal
      and coalesce(t.balance, 0) = :balance
      and coalesce(t.summary, '') = :summary
  """)
  boolean existsSameTransaction(
      @Param("bankName") String bankName,
      @Param("accountNo") String accountNo,
      @Param("txDate") LocalDate txDate,
      @Param("deposit") Long deposit,
      @Param("withdrawal") Long withdrawal,
      @Param("balance") Long balance,
      @Param("summary") String summary
  );

  @Query("""
    select t
    from BankTransaction t
    where (:bank = '' or t.bankName like concat('%', :bank, '%'))
      and (:accountNo = '' or t.accountNo like concat('%', :accountNo, '%'))
      and (:startDate is null or t.txDate >= :startDate)
      and (:endDate is null or t.txDate <= :endDate)
    order by t.txDate desc, t.id desc
  """)
  List<BankTransaction> search(
      @Param("bank") String bank,
      @Param("accountNo") String accountNo,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate
  );

  // ✅ 돋보기용: (은행명/계좌번호) distinct 목록
  @Query("""
    select distinct t.bankName, t.accountNo
    from BankTransaction t
    where (:kw = '' or t.bankName like concat('%', :kw, '%') or t.accountNo like concat('%', :kw, '%'))
    order by t.bankName asc, t.accountNo asc
  """)
  List<Object[]> distinctAccounts(@Param("kw") String kw);
}