package com.jdend.erp.contract.scan.support;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * 올릴 수 있는 스캔 파일의 조건 — PDF · JPG · PNG, 한 장에 10MB 까지.
 *
 * <p>확장자만 보지 않고 앞머리 몇 바이트(매직 넘버)까지 확인한다. 확장자는 누구나
 * 바꿔 달 수 있어서, 문서로 보이는 파일이 실은 다른 것일 수 있다. 서버가 그 파일을
 * 실행할 일은 없지만, 받아서 여는 사람의 PC 에서 열린다는 점이 남는다.
 *
 * <p>내려줄 때 쓰는 Content-Type 도 브라우저가 보내온 값을 믿지 않고 여기서 정한다.
 * 같은 PNG 를 브라우저마다 다르게 적어 보내는 일이 있고, 그러면 미리보기가 어떤
 * 브라우저에서는 열리고 어떤 브라우저에서는 내려받기로 떨어진다.
 */
public final class ScanFileRule {

  /** 한 파일 최대 크기. application.yml 의 multipart max-file-size 와 같게 둔다. */
  public static final long MAX_SIZE = 10L * 1024 * 1024;

  public static final List<String> ALLOWED_EXTENSIONS = List.of("pdf", "jpg", "jpeg", "png");

  /** 화면 안내용 — "PDF · JPG · PNG" */
  public static final String ALLOWED_LABEL = "PDF · JPG · PNG";

  private ScanFileRule() {}

  /** 검사를 통과하면 저장·전송에 쓸 Content-Type 을 돌려준다. */
  public static String verify(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("파일이 비어 있습니다.");
    }
    if (file.getSize() > MAX_SIZE) {
      throw new IllegalArgumentException(
          "10MB 를 넘는 파일은 올릴 수 없습니다: " + safeName(file) + " ("
          + (file.getSize() / 1024 / 1024) + "MB)");
    }

    String ext = extensionOf(safeName(file));
    if (!ALLOWED_EXTENSIONS.contains(ext)) {
      throw new IllegalArgumentException(
          "올릴 수 없는 형식입니다: " + safeName(file) + " — " + ALLOWED_LABEL + " 만 됩니다.");
    }

    byte[] head = headOf(file);
    String sniffed = sniff(head);
    if (sniffed == null) {
      throw new IllegalArgumentException(
          "파일 내용이 " + ALLOWED_LABEL + " 가 아닙니다: " + safeName(file)
          + " — 확장자만 바꾼 파일은 올릴 수 없습니다.");
    }
    if (!sniffed.equals(expectedType(ext))) {
      throw new IllegalArgumentException(
          "확장자와 파일 내용이 다릅니다: " + safeName(file)
          + " — 내용은 " + label(sniffed) + " 입니다.");
    }
    return sniffed;
  }

  /** 파일 이름에서 소문자 확장자만. 없으면 빈 문자열. */
  public static String extensionOf(String name) {
    int dot = name == null ? -1 : name.lastIndexOf('.');
    return (dot < 0 || dot == name.length() - 1)
        ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String expectedType(String ext) {
    return switch (ext) {
      case "pdf" -> "application/pdf";
      case "png" -> "image/png";
      default    -> "image/jpeg";   // jpg, jpeg
    };
  }

  private static String label(String contentType) {
    return switch (contentType) {
      case "application/pdf" -> "PDF";
      case "image/png" -> "PNG";
      default -> "JPG";
    };
  }

  /** 앞머리 바이트로 실제 형식을 본다. 아는 형식이 아니면 null. */
  private static String sniff(byte[] h) {
    if (h.length >= 4 && h[0] == 0x25 && h[1] == 0x50 && h[2] == 0x44 && h[3] == 0x46) {
      return "application/pdf";                       // %PDF
    }
    if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47
        && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
      return "image/png";
    }
    if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    return null;
  }

  private static byte[] headOf(MultipartFile file) {
    try (InputStream in = file.getInputStream()) {
      return in.readNBytes(8);
    } catch (IOException e) {
      throw new IllegalArgumentException("파일을 읽을 수 없습니다: " + safeName(file));
    }
  }

  private static String safeName(MultipartFile file) {
    String n = file.getOriginalFilename();
    return (n == null || n.isBlank()) ? "(이름없음)" : n;
  }
}
