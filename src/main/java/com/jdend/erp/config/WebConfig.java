package com.jdend.erp.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

  private final AuthInterceptor authInterceptor;

  /**
   * ERP 화면을 서빙하는 도메인.
   * 화면과 API를 같은 서버가 내려주므로(config.js 의 API_BASE_URL 이 빈 문자열) 보통은
   * 동일 출처라 CORS 가 필요 없지만, 다른 도메인에서 붙일 때를 위해 설정으로 빼 둔다.
   */
  @Value("${app.cors.erp-origins:https://erp.planbloan.co.kr,http://localhost:8080,http://localhost:8081}")
  private String[] erpOrigins;

  /** 홈페이지에서 비로그인으로 호출하는 구독결제 엔드포인트 허용 도메인 */
  @Value("${app.cors.public-origins:https://planbloan.co.kr,https://www.planbloan.co.kr,http://localhost:8080,http://localhost:8081}")
  private String[] publicOrigins;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // ERP 내부 API: 세션 쿠키와 함께 호출되므로 allowCredentials 필요
    registry.addMapping("/api/**")
      .allowedOriginPatterns(erpOrigins)
      .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
      .allowedHeaders("*")
      .allowCredentials(true);

    // 구독 결제 공개 API: 홈페이지에서 비로그인 호출
    registry.addMapping("/api/subscription/kiwoom/**")
      .allowedOriginPatterns(publicOrigins)
      .allowedMethods("GET", "POST", "OPTIONS")
      .allowedHeaders("*")
      .allowCredentials(false);
  }

  /**
   * 화면 파일(js·css·html)은 캐시하지 않게 한다.
   *
   * Cloudflare 가 js·css 를 기본 4시간 캐시해서, 배포한 뒤에도 옛 파일이 내려갔다.
   * 사이드바에 새 메뉴를 넣었는데 화면에 안 보이는 일이 실제로 있었다
   * (cf-cache-status: HIT / Age: 3189). 브라우저 강력새로고침으로도 풀리지 않는다.
   *
   * 원본이 no-cache 를 내려주면 Cloudflare 도 그 지시를 따라 매번 서버에 확인한다.
   * ERP 는 사내에서 쓰는 화면이라 이 정도 왕복은 체감되지 않고,
   * 배포한 것이 바로 보이는 쪽이 훨씬 중요하다.
   */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.noCache().mustRevalidate());
  }

  // BUG-12-01: 삭제된(비활성화된) 사용자의 기존 세션 무효화 인터셉터 등록
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(
            "/api/auth/**",
            "/api/company-applications/**",
            "/api/subscription/kiwoom/**"   // 구독 결제 공개 엔드포인트 (비로그인)
        );
  }
}
