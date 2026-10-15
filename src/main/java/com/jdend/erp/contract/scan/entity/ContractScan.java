package com.jdend.erp.contract.scan.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 채권 스캔 서류 — 대출채권 한 건에 딸린 스캔 파일 한 장의 정보.
 *
 * <p>파일 자체는 디스크에 두고 여기에는 어디에 무엇으로 저장했는지만 적는다.
 * DB 에 넣으면 백업·복제가 무거워지고, 서류는 한번 올리면 거의 읽기만 하기 때문이다.
 *
 * <p>기준은 계약번호다. 고객 기준이 아니다 — 같은 고객이 두 건을 빌렸으면 계약서도
 * 추심 서류도 건별로 따로 남아야 하고, 한 건을 상각하거나 넘길 때 그 건의 서류만
 * 묶여 나와야 한다.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@Entity
@Table(name = "contract_scans",
       indexes = {
         @Index(name = "idx_scan_contract", columnList = "contract_number"),
         @Index(name = "idx_scan_category", columnList = "contract_number,doc_category")
       })
public class ContractScan {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 대출채권 계약번호 */
  @Column(name = "contract_number", nullable = false, length = 30)
  private String contractNumber;

  /** 서류구분 — 계약서 / 추심관련서류 / 법적절차서류 */
  @Column(name = "doc_category", nullable = false, length = 30)
  private String docCategory;

  /** 서류명 — 비우면 올린 파일 이름을 쓴다 */
  @Column(name = "doc_title", length = 200)
  private String docTitle;

  /** 서류일자 — 작성·접수·송달된 날. 올린 날(createdAt)과는 다르다. */
  @Column(name = "doc_date")
  private LocalDate docDate;

  /** 올릴 때의 원래 파일 이름. 내려줄 때 이 이름으로 준다. */
  @Column(name = "original_name", nullable = false, length = 255)
  private String originalName;

  /** 디스크에 저장된 이름(UUID). 원래 이름을 그대로 쓰지 않는 이유는 아래 서비스 설명 참고. */
  @Column(name = "stored_name", nullable = false, length = 255)
  private String storedName;

  @Column(name = "content_type", length = 100)
  private String contentType;

  @Column(name = "file_size")
  private Long fileSize;

  /** 올린 사람 로그인 아이디 */
  @Column(name = "uploaded_by", length = 50)
  private String uploadedBy;

  @Column(name = "memo", length = 500)
  private String memo;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;
}
