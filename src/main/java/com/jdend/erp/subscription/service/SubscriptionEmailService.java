package com.jdend.erp.subscription.service;

import com.jdend.erp.subscription.entity.SubscriptionPayment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

/**
 * 구독 결제 완료 시 관리자(lg56607213@gmail.com)에게 이메일 알림 발송.
 * 이메일 발송 실패해도 결제 처리는 정상 진행 (예외를 삼킴).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionEmailService {

    private final JavaMailSender mailSender;

    @Value("${admin.email}")
    private String adminEmail;

    /** ERP 접속 도메인. 배포 환경에 따라 바뀌므로 하드코딩하지 않는다. */
    @Value("${app.erp-base-url:https://erp.planbloan.co.kr}")
    private String erpBaseUrl;

    public void sendPaymentCompleteNotice(SubscriptionPayment payment) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(adminEmail);
            message.setSubject("[JDEND ERP] 새 구독 결제 완료 - " + payment.getCompanyName());

            String createdAt = payment.getCreatedAt() != null
                    ? payment.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : "-";

            String body =
                    "━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "새 구독 신청이 결제 완료되었습니다.\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    "회사명: " + payment.getCompanyName() + "\n" +
                    "신청자: " + payment.getApplicantName() + "\n" +
                    "연락처: " + payment.getPhone() + "\n" +
                    "이메일: " + payment.getEmail() + "\n" +
                    "요금제: " + payment.getPlanName() + "\n" +
                    "결제금액: " + String.format("%,d", payment.getAmount()) + "원\n" +
                    "결제일시: " + createdAt + "\n" +
                    "주문번호: " + payment.getOrderId() + "\n" +
                    "카드사: " + (payment.getCardName() != null ? payment.getCardName() : "-") + "\n" +
                    "승인번호: " + (payment.getAuthno() != null ? payment.getAuthno() : "-") + "\n\n" +
                    "▶ ERP 관리자 화면에서 계정을 생성해주세요.\n" +
                    erpBaseUrl + "\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━";

            message.setText(body);
            mailSender.send(message);
            log.info("[SubscriptionEmail] 관리자 이메일 발송 완료: to={}", adminEmail);
        } catch (Exception e) {
            log.error("[SubscriptionEmail] 이메일 발송 실패 (결제 처리는 정상 완료): {}", e.getMessage());
        }
    }
}
