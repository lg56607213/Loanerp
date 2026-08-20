package com.jdend.erp.accounting.voucher.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vouchers")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Voucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_no", nullable = false, length = 50, unique = true)
    private String voucherNo;

    @Column(name = "voucher_date", nullable = false)
    private LocalDate voucherDate;

    @Column(name = "contract_number", length = 50)
    private String contractNumber;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    // ✅ 추가: 승인 상태 ("대기" | "승인")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "memo", length = 255)
    private String memo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @Builder.Default
    private List<VoucherLine> lines = new ArrayList<>();

    public void addLine(VoucherLine line) {
        line.setVoucher(this);
        this.lines.add(line);
    }
}