package com.jdend.erp.accounting.voucher.service;

import com.jdend.erp.accounting.voucher.entity.Voucher;
import com.jdend.erp.accounting.voucher.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 승인된 전표가 걸려 있으면 원본 기록을 지우지 못하게 막는다.
 *
 * 승인된 전표는 이미 장부(재무제표·자금일보)에 반영된 것으로 본다.
 * 그 전표를 만든 수납·대출을 그대로 지우면 장부와 업무 기록이 어긋난다.
 * 실제로 전표만 지워 수납 기록이 사라지고 원금만 줄어 있던 채권이 있었다.
 *
 * 그래서 순서를 강제한다.
 *   1. 회계관리 > 전표등록 > 전표승인 에서 그 전표를 '대기'로 되돌린다
 *   2. 그다음 원본(수납·대출)을 취소한다
 */
@Component
@RequiredArgsConstructor
public class VoucherApprovalGuard {

  public static final String APPROVED = "승인";

  private final VoucherRepository voucherRepository;

  /**
   * 전표 하나가 승인 상태면 막는다. 수납처럼 전표를 하나만 참조하는 경우에 쓴다.
   *
   * @param voucherId 원본이 들고 있는 전표 id. null 이면 막지 않는다.
   * @param what      무엇을 취소하려는지 (메시지에 들어간다). 예: "수납"
   */
  public void requireNotApproved(Long voucherId, String what) {
    if (voucherId == null) return;
    Voucher v = voucherRepository.findById(voucherId).orElse(null);
    if (v == null) return;                       // 전표가 이미 없으면 막을 이유가 없다
    if (!APPROVED.equals(v.getStatus())) return;

    throw new IllegalStateException(block(what, "전표 " + v.getVoucherNo()));
  }

  /**
   * 이 채권에 승인된 전표가 하나라도 있으면 막는다.
   * 대출처럼 전표가 여러 건 딸릴 수 있는 경우에 쓴다.
   */
  public void requireNoApprovedForContract(String contractNumber, String what) {
    if (contractNumber == null || contractNumber.isBlank()) return;
    if (!voucherRepository.existsByContractNumberAndStatus(contractNumber, APPROVED)) return;

    throw new IllegalStateException(block(what, "채권 " + contractNumber + " 의 전표"));
  }

  private String block(String what, String target) {
    return what + "을(를) 취소하려면 먼저 " + target + "의 승인을 취소해야 합니다."
         + " 회계관리 > 전표등록 > 전표승인 에서 해당 전표를 선택하고 '대기' 버튼을 누른 뒤 다시 시도하세요."
         + " 승인된 전표는 이미 장부에 반영된 것이라, 그대로 두고 원본만 지우면 장부가 어긋납니다.";
  }
}
