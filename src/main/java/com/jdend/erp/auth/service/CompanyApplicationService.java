package com.jdend.erp.auth.service;

import com.jdend.erp.auth.dto.CompanyApplicationRequest;
import com.jdend.erp.auth.dto.CompanyApplicationResponse;
import com.jdend.erp.auth.entity.CompanyApplication;
import com.jdend.erp.auth.entity.CompanyUser;
import com.jdend.erp.auth.entity.LoginUser;
import com.jdend.erp.auth.repository.CompanyApplicationRepository;
import com.jdend.erp.auth.repository.CompanyUserRepository;
import com.jdend.erp.auth.repository.LoginUserRepository;
import com.jdend.erp.config.TenantDatabaseService;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyApplicationService {

    private final CompanyApplicationRepository applicationRepository;
    private final LoginUserRepository loginUserRepository;
    private final CompanyUserRepository companyUserRepository;
    private final TenantDatabaseService tenantDatabaseService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    /** 관리자 알림 수신 이메일: 미설정 시 발신자 주소(spring.mail.username) 사용 */
    @Value("${app.admin-email:}")
    private String adminEmail;

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    // =========================================================
    // 공개 API - 신청 접수 (로그인 불필요)
    // =========================================================

    @Transactional
    public void create(CompanyApplicationRequest req) {
        validateRequest(req);

        CompanyApplication application = CompanyApplication.builder()
                .companyName(req.getCompanyName().trim())
                .representativeName(req.getRepresentativeName().trim())
                .phone(req.getPhone().trim())
                .email(req.getEmail().trim())
                .vehicleCount(req.getVehicleCount())
                .inquiry(req.getInquiry())
                .status("PENDING")
                .build();

        applicationRepository.save(application);

        // 운영자에게 이메일 알림 (실패해도 신청 자체는 성공)
        sendAdminNotification(application);
    }

    // =========================================================
    // 운영자 전용
    // =========================================================

    @Transactional(readOnly = true)
    public List<CompanyApplicationResponse> list(HttpSession session) {
        authService.requireAdmin(session);
        return applicationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(CompanyApplicationResponse::new)
                .toList();
    }

    /** 승인: 회사 통합계정 + COMPANY_ADMIN 사용자 자동 생성, 신청자 이메일 발송 */
    @Transactional
    public void approve(Long id, HttpSession session) {
        authService.requireAdmin(session);

        CompanyApplication application = applicationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("신청을 찾을 수 없습니다. id=" + id));

        if ("APPROVED".equals(application.getStatus())) {
            throw new RuntimeException("이미 승인된 신청입니다.");
        }
        if ("REJECTED".equals(application.getStatus())) {
            throw new RuntimeException("거절된 신청은 재승인할 수 없습니다.");
        }

        // 회사 코드 자동 생성 (알파벳+숫자만, 소문자, 최대 10자, 중복 시 숫자 suffix)
        String companyCode = generateCompanyCode(application.getCompanyName());
        String targetDb = tenantDatabaseService.normalizeTenantDb(companyCode, companyCode);

        // 임시 비밀번호 생성 (랜덤 8자리 영문+숫자)
        String tempPassword = generateTempPassword();
        String encodedPassword = passwordEncoder.encode(tempPassword);

        // 1) login_users에 회사 통합 계정 INSERT
        LoginUser savedLoginUser = loginUserRepository.save(
                LoginUser.builder()
                        .loginId(companyCode)
                        .loginPassword(encodedPassword)
                        .companyName(application.getCompanyName())
                        .targetDb(targetDb)
                        .role("COMPANY")
                        .isActive(true)
                        .build()
        );

        // 2) company_users에 COMPANY_ADMIN INSERT (통합 계정과 동일한 id/password)
        companyUserRepository.save(
                CompanyUser.builder()
                        .companyId(savedLoginUser.getId())
                        .userLoginId(companyCode)
                        .userPassword(encodedPassword)
                        .role("COMPANY_ADMIN")
                        .isActive(true)
                        .build()
        );

        // 3) 회사 전용 DB 생성 + 스키마 복제 + 재무제표 기본 계정 복제
        tenantDatabaseService.ensureTenantDatabase(targetDb);

        // 4) 신청자 이메일로 로그인 정보 발송
        sendApprovalEmail(application, companyCode, tempPassword);

        // 5) 신청 상태 APPROVED로 변경
        application.setStatus("APPROVED");
    }

    /** 거절 */
    @Transactional
    public void reject(Long id, HttpSession session) {
        authService.requireAdmin(session);

        CompanyApplication application = applicationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("신청을 찾을 수 없습니다. id=" + id));

        if ("APPROVED".equals(application.getStatus())) {
            throw new RuntimeException("이미 승인된 신청은 거절할 수 없습니다.");
        }

        application.setStatus("REJECTED");
    }

    // =========================================================
    // 내부 헬퍼
    // =========================================================

    private void validateRequest(CompanyApplicationRequest req) {
        if (req == null) throw new IllegalArgumentException("요청값이 없습니다.");
        if (isBlank(req.getCompanyName())) throw new IllegalArgumentException("회사명을 입력해주세요.");
        if (isBlank(req.getRepresentativeName())) throw new IllegalArgumentException("대표자명을 입력해주세요.");
        if (isBlank(req.getPhone())) throw new IllegalArgumentException("연락처를 입력해주세요.");
        if (isBlank(req.getEmail())) throw new IllegalArgumentException("이메일을 입력해주세요.");
        if (!req.getEmail().trim().contains("@")) throw new IllegalArgumentException("유효한 이메일 주소를 입력해주세요.");
    }

    /**
     * 회사명 -> 회사 코드 자동 생성
     * 규칙: 알파벳+숫자만 추출 → 소문자 → 최대 10자 → 중복 시 숫자 suffix (2, 3, ...)
     */
    private String generateCompanyCode(String companyName) {
        String cleaned = companyName.toLowerCase().replaceAll("[^a-z0-9]", "");
        String base = cleaned.length() > 10 ? cleaned.substring(0, 10) : cleaned;

        if (base.isBlank()) base = "company";

        if (!loginUserRepository.existsByLoginId(base) && !loginUserRepository.existsByTargetDb("loan_company_" + base)) return base;

        for (int i = 2; i <= 999; i++) {
            String candidate = base + i;
            if (!loginUserRepository.existsByLoginId(candidate) && !loginUserRepository.existsByTargetDb("loan_company_" + candidate)) return candidate;
        }

        throw new RuntimeException("회사 코드 생성에 실패했습니다. 운영자에게 문의해주세요.");
    }

    /** 랜덤 8자리 임시 비밀번호 (영문 대소문자 + 숫자) */
    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    /** 운영자에게 새 신청 알림 이메일 발송 */
    private void sendAdminNotification(CompanyApplication a) {
        String to = isBlank(adminEmail) ? senderEmail : adminEmail;
        if (isBlank(to) || isBlank(senderEmail)) {
            log.warn("[온보딩] 이메일 미설정 - 관리자 신청 알림 생략 company={}", a.getCompanyName());
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(senderEmail);
            helper.setTo(to);
            helper.setSubject("[JDEND ERP] 새 무료체험 신청 - " + a.getCompanyName());
            helper.setText(buildAdminNotificationHtml(a), true);
            mailSender.send(message);
            log.info("[온보딩] 관리자 알림 발송 완료 company={} to={}", a.getCompanyName(), to);
        } catch (Exception e) {
            log.error("[온보딩] 관리자 알림 발송 실패 company={} error={}", a.getCompanyName(), e.getMessage());
        }
    }

    /** 신청자에게 승인 + 계정 정보 이메일 발송 */
    private void sendApprovalEmail(CompanyApplication a, String loginId, String tempPassword) {
        if (isBlank(senderEmail)) {
            log.warn("[온보딩] 이메일 미설정 - 승인 알림 생략 company={}", a.getCompanyName());
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(senderEmail);
            helper.setTo(a.getEmail());
            helper.setSubject("[JDEND 렌터카 ERP] 무료체험 계정이 준비되었습니다");
            helper.setText(buildApprovalHtml(a, loginId, tempPassword), true);
            mailSender.send(message);
            log.info("[온보딩] 승인 이메일 발송 완료 company={} to={}", a.getCompanyName(), a.getEmail());
        } catch (Exception e) {
            log.error("[온보딩] 승인 이메일 발송 실패 company={} to={} error={}",
                    a.getCompanyName(), a.getEmail(), e.getMessage());
        }
    }

    private String buildAdminNotificationHtml(CompanyApplication a) {
        return String.format("""
            <!DOCTYPE html><html lang="ko"><head><meta charset="UTF-8"></head>
            <body style="font-family:Arial,sans-serif;padding:20px;">
            <h2 style="color:#1b253a;">새 무료체험 신청이 접수되었습니다</h2>
            <table style="border-collapse:collapse;width:100%%;">
              <tr><td style="padding:8px;border:1px solid #e5e7eb;font-weight:700;">회사명</td>
                  <td style="padding:8px;border:1px solid #e5e7eb;">%s</td></tr>
              <tr><td style="padding:8px;border:1px solid #e5e7eb;font-weight:700;">대표자</td>
                  <td style="padding:8px;border:1px solid #e5e7eb;">%s</td></tr>
              <tr><td style="padding:8px;border:1px solid #e5e7eb;font-weight:700;">연락처</td>
                  <td style="padding:8px;border:1px solid #e5e7eb;">%s</td></tr>
              <tr><td style="padding:8px;border:1px solid #e5e7eb;font-weight:700;">이메일</td>
                  <td style="padding:8px;border:1px solid #e5e7eb;">%s</td></tr>
              <tr><td style="padding:8px;border:1px solid #e5e7eb;font-weight:700;">차량 대수</td>
                  <td style="padding:8px;border:1px solid #e5e7eb;">%s</td></tr>
              <tr><td style="padding:8px;border:1px solid #e5e7eb;font-weight:700;">문의사항</td>
                  <td style="padding:8px;border:1px solid #e5e7eb;">%s</td></tr>
            </table>
            <p style="margin-top:16px;">
              <a href="https://www.rentcarerp.com/admin_users.html" style="color:#2563eb;">
                관리자 페이지에서 승인하기
              </a>
            </p>
            </body></html>
            """,
                a.getCompanyName(),
                a.getRepresentativeName(),
                a.getPhone(),
                a.getEmail(),
                a.getVehicleCount() != null ? a.getVehicleCount() : "-",
                a.getInquiry() != null ? a.getInquiry() : "-"
        );
    }

    private String buildApprovalHtml(CompanyApplication a, String loginId, String tempPassword) {
        String contactEmail = isBlank(senderEmail) ? "support@rentcarerp.com" : senderEmail;
        return String.format("""
            <!DOCTYPE html><html lang="ko"><head><meta charset="UTF-8"></head>
            <body style="font-family:Arial,sans-serif;padding:20px;background:#f5f7fb;">
            <div style="max-width:600px;margin:0 auto;background:#fff;border-radius:12px;
                        overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,.08);">
              <div style="background:#1b253a;padding:24px 32px;">
                <div style="color:#94aac4;font-size:12px;margin-bottom:4px;">JDEND 렌터카 ERP</div>
                <div style="color:#fff;font-size:20px;font-weight:700;">무료체험 계정이 준비되었습니다</div>
              </div>
              <div style="padding:24px 32px;">
                <p>안녕하세요, <strong>%s</strong> 대표자님.</p>
                <p>JDEND 렌터카 ERP 무료체험 신청이 승인되었습니다.<br>
                   아래 계정 정보로 로그인하세요.</p>
                <table style="border-collapse:collapse;width:100%%;margin:16px 0;">
                  <tr>
                    <td style="padding:10px 16px;background:#f8fafc;font-weight:700;border:1px solid #e5e7eb;">로그인 주소</td>
                    <td style="padding:10px 16px;border:1px solid #e5e7eb;">
                      <a href="https://www.rentcarerp.com/login.html">https://www.rentcarerp.com/login.html</a>
                    </td>
                  </tr>
                  <tr>
                    <td style="padding:10px 16px;background:#f8fafc;font-weight:700;border:1px solid #e5e7eb;">통합 아이디</td>
                    <td style="padding:10px 16px;border:1px solid #e5e7eb;font-family:monospace;font-size:16px;">%s</td>
                  </tr>
                  <tr>
                    <td style="padding:10px 16px;background:#f8fafc;font-weight:700;border:1px solid #e5e7eb;">임시 비밀번호</td>
                    <td style="padding:10px 16px;border:1px solid #e5e7eb;font-family:monospace;font-size:16px;color:#dc2626;">%s</td>
                  </tr>
                </table>
                <p style="color:#6b7280;font-size:13px;">
                  로그인 후 비밀번호를 변경하시기 바랍니다.<br>
                  통합 아이디/비밀번호만 입력하면 회사관리자(COMPANY_ADMIN)로 자동 로그인됩니다.
                </p>
                <p style="color:#6b7280;font-size:13px;">
                  문의: <a href="mailto:%s">%s</a>
                </p>
              </div>
            </div>
            </body></html>
            """,
                a.getRepresentativeName(), loginId, tempPassword, contactEmail, contactEmail
        );
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
