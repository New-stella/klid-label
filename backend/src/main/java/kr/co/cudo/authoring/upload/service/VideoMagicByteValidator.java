package kr.co.cudo.authoring.upload.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 완료 시 매직바이트 기반 영상 컨테이너 검증 (CWE-434).
 *
 * <p>확장자만으로는 위조 가능하므로, TUS 완료 시점에 실제 파일 헤더로 컨테이너를 확인한다.
 * 지원: mp4/mov(ISO BMFF — 4~8바이트 {@code ftyp}), webm/mkv(EBML 0x1A45DFA3),
 * avi(RIFF....AVI ).
 *
 * <p>MED-2: ISO BMFF 는 {@code ftyp} 시그니처만으로는 polyglot(앞 4바이트만 ftyp 로 위장하고
 * 뒤에 악성 페이로드) 우회가 가능하므로 추가 검증을 수행한다.
 * <ul>
 *   <li>ftyp box size(0~3바이트, big-endian)가 합리적 범위(8 ~ 1MB) 인지 — 거대/0 size 거부.</li>
 *   <li>major_brand(8~11바이트)가 영상 컨테이너 allowlist(isom/mp42/qt /avc1/M4V 등) 인지 —
 *       헤더에 12바이트 이상이 존재하는 경우 brand 가 allowlist 밖이면 거부.</li>
 * </ul>
 * 12바이트 미만의 짧은 ftyp 헤더는 brand 를 단정할 수 없으므로 size 검증만 적용하고,
 * 최종 컨테이너 유효성은 완료 경로의 ffprobe(duration 추출 성공 = 유효 컨테이너) 가 2차로 확인한다.
 */
public final class VideoMagicByteValidator {

    /** MED-2: ISO BMFF major_brand allowlist (영상 컨테이너만). */
    private static final Set<String> ALLOWED_BRANDS = Set.of(
            "isom", "iso2", "iso4", "iso5", "iso6",
            "mp41", "mp42", "mp4v", "avc1", "M4V ", "M4A ",
            "qt  ", "3gp4", "3gp5", "3g2a", "dash", "mmp4");

    /** MED-2: ftyp box size 상한 — 정상 ftyp box 는 수십 바이트, 1MB 면 충분히 여유. */
    private static final long MAX_FTYP_BOX_SIZE = 1024L * 1024L;

    private VideoMagicByteValidator() {
    }

    /**
     * @return 영상 컨테이너 시그니처가 확인되면 true
     */
    public static boolean isVideoContainer(Path file) {
        byte[] head = readHead(file, 16);
        if (head == null) {
            return false;
        }
        return isIsoBmff(head) || isEbml(head) || isRiffAvi(head);
    }

    /**
     * ISO BMFF (mp4/mov/m4v) — 4~8바이트가 ASCII "ftyp" (최소 8바이트 필요).
     * MED-2: box size 합리성 + (헤더가 충분하면) major_brand allowlist 동반 검증.
     */
    private static boolean isIsoBmff(byte[] h) {
        if (h.length < 8 || h[4] != 'f' || h[5] != 't' || h[6] != 'y' || h[7] != 'p') {
            return false;
        }
        // box size(0~3바이트, big-endian) 합리성 — 0/거대 size 는 polyglot 위장 신호.
        long boxSize = ((long) (h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16)
                | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
        if (boxSize < 8 || boxSize > MAX_FTYP_BOX_SIZE) {
            return false;
        }
        // major_brand(8~11바이트) allowlist — 헤더에 brand 가 존재할 때만 강제.
        if (h.length >= 12) {
            String brand = new String(h, 8, 4, StandardCharsets.ISO_8859_1);
            return ALLOWED_BRANDS.contains(brand);
        }
        return true;
    }

    /** Matroska/WebM — EBML 헤더 0x1A 0x45 0xDF 0xA3 (최소 4바이트 필요). */
    private static boolean isEbml(byte[] h) {
        return h.length >= 4
                && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45
                && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3;
    }

    /** AVI — RIFF....AVI (최소 12바이트 필요). */
    private static boolean isRiffAvi(byte[] h) {
        return h.length >= 12
                && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'A' && h[9] == 'V' && h[10] == 'I' && h[11] == ' ';
    }

    private static byte[] readHead(Path file, int n) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(n);
        } catch (IOException e) {
            return null;
        }
    }
}
