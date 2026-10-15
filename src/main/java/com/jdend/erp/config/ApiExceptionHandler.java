package com.jdend.erp.config;

import com.jdend.erp.auth.exception.ForbiddenException;
import com.jdend.erp.auth.exception.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<?> handleForbidden(ForbiddenException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
      .body(Map.of("message", e.getMessage()));
  }

  // BUG-13-06: 리소스 미존재 → 404
  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<?> handleNotFound(NotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
      .body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> handleBadRequest(IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(Map.of("message", e.getMessage()));
  }

  // BUG-8차-02: DB 유니크 제약 위반 → 409 (SQL 내부 정보 노출 방지)
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<?> handleDataIntegrity(DataIntegrityViolationException e) {
    String msg = e.getMessage() != null && e.getMessage().contains("Duplicate")
        ? "이미 등록된 데이터입니다. (중복된 값이 있습니다.)"
        : "데이터 무결성 오류가 발생했습니다.";
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", msg));
  }

  // BUG-7차-04: HTTP 메서드 미지원 → 405
  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
      .body(Map.of("message", "지원하지 않는 HTTP 메서드입니다: " + e.getMethod()));
  }

  // BUG-6차-02: 필수 파라미터 누락 → 400
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<?> handleMissingParam(MissingServletRequestParameterException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(Map.of("message", "필수 파라미터 누락: " + e.getParameterName()));
  }

  /**
   * 업로드 용량 초과 → 400.
   *
   * 스프링이 멀티파트를 읽는 단계에서 끊으므로 각 화면의 검사까지 오지 못한다.
   * 그대로 두면 "Maximum upload size exceeded" 라는 영문 메시지가 사용자에게 보인다.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> handleUploadTooLarge(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(Map.of("message", "파일 용량이 너무 큽니다. 한 파일은 10MB, 한 번에 올리는 전체는 30MB 까지입니다."));
  }

  /** 멀티파트 요청에 파일 칸이 아예 없을 때 → 400 (예: 파일을 고르지 않고 보냄) */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<?> handleMissingPart(MissingServletRequestPartException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(Map.of("message", "올릴 파일을 선택하세요."));
  }

  // BUG-6차-02: 미존재 경로 → 404
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<?> handleNotFound(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
      .body(Map.of("message", "경로를 찾을 수 없습니다: " + e.getResourcePath()));
  }

  // BUG-6차-01: 경로 변수 타입 변환 실패 → 400
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(Map.of("message", "잘못된 요청 파라미터: " + e.getName() + " = " + e.getValue()));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<?> handleRuntime(RuntimeException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> handleServerError(Exception e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(Map.of("message", "서버 오류: " + e.getMessage()));
  }
}
