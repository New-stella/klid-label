package kr.co.cudo.authoring.portal.service;

import java.util.Locale;
import java.util.Optional;

/**
 * V107 포털 이미지 업로드 매직바이트 검증 (CWE-434 / OWASP A08:2025).
 *
 * <p>확장자만으로는 위조 가능하므로 파일 헤더 시그니처로 실제 포맷을 확정한다. JPEG/PNG 만 허용하고
 * 그 외(SVG {@code <?xml}/{@code <svg}, GIF, BMP, WEBP, HTML 등)는 감지 실패로 거부한다.
 * SVG 는 XML 기반이라 브라우저에서 실행 가능한 스크립트를 품을 수 있어(저장형 XSS 벡터) 명시적으로 제외한다.
 *
 * <p>감지된 포맷은 서빙 시 {@code Content-Type} 확정(확장자 추정 금지)과 확장자↔시그니처 정합
 * 검증(예: .png 인데 JPEG 시그니처면 거부)에 사용한다. {@link VideoMagicByteValidator} 스타일을 준수한다.
 */
public final class ImageMagicByteValidator {

    /** 헤더 판독에 필요한 최소 바이트 수(PNG 8바이트 시그니처 커버). */
    public static final int HEADER_BYTES = 16;

    private ImageMagicByteValidator() {
    }

    /** 허용 이미지 포맷 — 매직바이트로 확정되며 MIME/확장자 정합의 단일 진실원. */
    public enum ImageFormat {
        JPEG("image/jpeg", "jpg", "jpeg"),
        PNG("image/png", "png");

        private final String mimeType;
        private final String[] extensions;

        ImageFormat(String mimeType, String... extensions) {
            this.mimeType = mimeType;
            this.extensions = extensions;
        }

        public String mimeType() {
            return mimeType;
        }

        /** 확장자(소문자)가 이 포맷과 정합하는지 — .png 에 JPEG 시그니처 같은 불일치를 거부하기 위함. */
        public boolean matchesExtension(String ext) {
            if (ext == null) {
                return false;
            }
            String lower = ext.toLowerCase(Locale.ROOT);
            for (String e : extensions) {
                if (e.equals(lower)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 헤더 바이트에서 이미지 포맷을 감지한다. JPEG/PNG 시그니처가 아니면 빈 Optional(거부).
     *
     * <p>header-only 위조(시그니처만 붙인 truncated 파일) 방어를 위해 시그니처 이상의 구조를 확인한다:
     * PNG 는 8바이트 시그니처에 더해 첫 청크 타입이 {@code IHDR} 인지(바이트 12~15) 검사하고, JPEG 는
     * 여기서 SOI({@code FF D8 FF})만 확인하되 파일 끝 EOI({@code FF D9})는 {@link #endsWithJpegEoi(byte[])}
     * 로 별도 검사한다(전체 디코드 없이 저비용). ImageIO 전체 디코드는 대용량 비용 때문에 사용하지 않는다.
     *
     * @param head 파일 선두 바이트(최소 {@link #HEADER_BYTES} 권장, 짧으면 판정 실패)
     */
    public static Optional<ImageFormat> detect(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        if (isJpeg(head)) {
            return Optional.of(ImageFormat.JPEG);
        }
        if (isPng(head)) {
            return Optional.of(ImageFormat.PNG);
        }
        return Optional.empty();
    }

    /**
     * JPEG 파일 끝이 EOI 마커({@code FF D9})로 끝나는지 — header-only truncated JPEG 차단.
     *
     * @param tail 파일 말미 바이트(최소 2바이트). 2바이트 미만이면 false.
     */
    public static boolean endsWithJpegEoi(byte[] tail) {
        return tail != null && tail.length >= 2
                && (tail[tail.length - 2] & 0xFF) == 0xFF && (tail[tail.length - 1] & 0xFF) == 0xD9;
    }

    /** JPEG — FF D8 FF (SOI + 마커 시작). */
    private static boolean isJpeg(byte[] h) {
        return h.length >= 3
                && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
    }

    /**
     * PNG — 89 50 4E 47 0D 0A 1A 0A (8바이트 시그니처) + 첫 청크 타입 {@code IHDR}(바이트 12~15).
     * 시그니처만 위조한 header-only truncated PNG 를 거부하기 위해 IHDR 청크 타입까지 확인한다.
     */
    private static boolean isPng(byte[] h) {
        return h.length >= 16
                && (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A
                // 바이트 12~15 = "IHDR" (0x49 0x48 0x44 0x52) — 첫 청크 타입 검사.
                && h[12] == 0x49 && h[13] == 0x48 && h[14] == 0x44 && h[15] == 0x52;
    }
}
