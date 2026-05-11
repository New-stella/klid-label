package kr.co.cudo.authoring.common.util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * 합성 placeholder 이미지 생성기 (DEV/LOCAL 전용).
 *
 * <p>실제 CCTV 영상이 없는 dev/local 환경에서 라벨링 캔버스가 빈 화면이 되지 않도록
 * 1920x1080 JPEG placeholder 를 자동 생성한다.
 *
 * <p>생성 결과:
 * <ul>
 *   <li>1920x1080 JPEG (이벤트별 배경색)</li>
 *   <li>중앙: "Frame #N · CCTV-XXX · {EVENT_KOR}" (큰 흰 글자)</li>
 *   <li>좌상단: timestamp</li>
 *   <li>우하단: "DEV PLACEHOLDER · NOT FOR DISTRIBUTION" 워터마크</li>
 * </ul>
 *
 * <p>보안:
 * <ul>
 *   <li>워터마크 명시로 운영 데이터로 오인 방지</li>
 *   <li>경로는 caller(SeedImageRunner)가 base path 검증 후 전달 — 본 클래스는 stateless</li>
 * </ul>
 */
public final class SeedImageGenerator {

    public static final int IMAGE_WIDTH = 1920;
    public static final int IMAGE_HEIGHT = 1080;

    /** 이벤트 코드 → 배경색 매핑 (mock UI 의 색상과 정합). */
    private static final Map<String, Color> EVENT_COLOR = Map.of(
            "EVT_FALL", new Color(0x6B, 0x72, 0x80),         // gray
            "EVT_VIOLENCE", new Color(0xDC, 0x26, 0x26),     // red
            "EVT_ACCIDENT", new Color(0x25, 0x63, 0xEB),     // blue
            "EVT_ABNORMAL", new Color(0xF5, 0x9E, 0x0B),     // orange
            "EVT_FLOOD", new Color(0x06, 0xB6, 0xD4),        // teal
            "EVT_FIRE", new Color(0x16, 0xA3, 0x4A)          // green
    );

    /** 이벤트 코드 → 한글 라벨. */
    private static final Map<String, String> EVENT_KOR = Map.of(
            "EVT_FALL", "쓰러짐",
            "EVT_VIOLENCE", "폭력",
            "EVT_ACCIDENT", "교통사고",
            "EVT_ABNORMAL", "이상행동",
            "EVT_FLOOD", "침수",
            "EVT_FIRE", "산불"
    );

    /** 한글 폰트 후보 (macOS / Linux / Windows / fallback). */
    private static final String[] FONT_CANDIDATES = {
            "Apple SD Gothic Neo",
            "AppleGothic",
            "Noto Sans CJK KR",
            "Noto Sans KR",
            "Malgun Gothic",
            "NanumGothic",
            "Arial Unicode MS",
            "SansSerif"
    };

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SeedImageGenerator() {
        // util — 인스턴스화 금지
    }

    // ========================================================================
    // Standalone main — DB 없이 dev-seed.sql 의 RAW 메타와 동일한 enumeration 으로
    // placeholder 165장을 한 방에 생성. (Spring/JDBC 부담 없음)
    //
    // 실행:
    //   ./gradlew runSeedImages
    //   또는
    //   java -cp build/classes/java/main kr.co.cudo.authoring.common.util.SeedImageGenerator [outDir]
    //
    // 인자: outDir (default: ./storage/raw)
    // ========================================================================

    /**
     * dev-seed.sql 과 동기 — 프레임이 존재하는 RAW_SN 목록.
     * COMPLETED 5건 (9001~9005) + PROCESSING 3건 (9016~9018) = 8건 × 5프레임 = 40장.
     * PENDING 영상(9026~9027)은 DB에 LS_DATA_SRC 없으므로 여기서도 제외.
     */
    private static final Object[][] SEED_RAW_META = {
            // {rawSn, cctvId, eventCode, capturedAt}
            {9001L, "CCTV-001", "EVT_FALL",     "2026-02-15T10:30:00"},
            {9002L, "CCTV-002", "EVT_VIOLENCE", "2026-02-16T11:00:00"},
            {9003L, "CCTV-003", "EVT_ACCIDENT", "2026-02-17T09:15:00"},
            {9004L, "CCTV-004", "EVT_ABNORMAL", "2026-02-18T14:00:00"},
            {9005L, "CCTV-005", "EVT_FLOOD",    "2026-02-19T16:30:00"},
            {9016L, "CCTV-016", "EVT_ABNORMAL", "2026-04-15T10:00:00"},
            {9017L, "CCTV-017", "EVT_FLOOD",    "2026-04-16T11:00:00"},
            {9018L, "CCTV-018", "EVT_FIRE",     "2026-04-17T14:30:00"},
    };

    private static final int FRAMES_PER_RAW = 5;

    public static void main(String[] args) throws IOException {
        String outDir = args.length > 0 ? args[0] : "./storage/raw";
        Path baseDir = Paths.get(outDir).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        System.out.println("[SeedImage] base = " + baseDir);

        int created = 0, skipped = 0;
        for (Object[] meta : SEED_RAW_META) {
            Long rawSn = (Long) meta[0];
            String cctvId = (String) meta[1];
            String evt = (String) meta[2];
            LocalDateTime captured = LocalDateTime.parse((String) meta[3]);
            for (int frame = 1; frame <= FRAMES_PER_RAW; frame++) {
                Path target = baseDir.resolve("seed").resolve(String.valueOf(rawSn))
                        .resolve("frame_" + frame + ".jpg").normalize();
                if (!target.startsWith(baseDir)) {
                    throw new IOException("path traversal — " + target);
                }
                boolean made = generate(target, evt, cctvId, frame, captured);
                if (made) created++; else skipped++;
            }
        }
        System.out.println("[SeedImage] DONE created=" + created + " skipped=" + skipped
                + " total=" + (SEED_RAW_META.length * FRAMES_PER_RAW));
    }

    /**
     * 단일 placeholder 이미지를 생성 후 지정 경로로 저장.
     *
     * @param target      저장할 절대 경로 (e.g., /Users/.../storage/raw/seed/9001/frame_1.jpg)
     * @param eventCode   이벤트 코드 (EVT_FALL 등). 알 수 없는 코드는 회색 처리.
     * @param cctvName    CCTV 식별자 (e.g., "CCTV-001")
     * @param frameNo     프레임 번호 (1-base)
     * @param capturedAt  캡처 시각 (좌상단 timestamp 표시용; null 이면 현재 시각)
     * @return 새로 생성했으면 true, 이미 존재하여 skip 했으면 false
     */
    public static boolean generate(Path target, String eventCode, String cctvName, int frameNo,
                                    LocalDateTime capturedAt) throws IOException {
        if (Files.exists(target)) {
            // 멱등성 — 이미 존재하면 skip
            return false;
        }
        Files.createDirectories(target.getParent());

        BufferedImage img = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 1) 배경 — 이벤트별 색상 + 그라디언트 느낌의 어두운 가장자리
            Color bg = EVENT_COLOR.getOrDefault(eventCode, new Color(0x37, 0x41, 0x51));
            g.setColor(bg);
            g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

            // 어두운 외곽 (vignette 모방) — 알파 블렌딩
            g.setColor(new Color(0, 0, 0, 80));
            int border = 80;
            g.fillRect(0, 0, IMAGE_WIDTH, border);
            g.fillRect(0, IMAGE_HEIGHT - border, IMAGE_WIDTH, border);
            g.fillRect(0, 0, border, IMAGE_HEIGHT);
            g.fillRect(IMAGE_WIDTH - border, 0, border, IMAGE_HEIGHT);

            // 2) 격자 (CCTV 영상 느낌 — 옅은 흰선)
            g.setColor(new Color(255, 255, 255, 30));
            int gridStep = 120;
            for (int x = gridStep; x < IMAGE_WIDTH; x += gridStep) {
                g.drawLine(x, 0, x, IMAGE_HEIGHT);
            }
            for (int y = gridStep; y < IMAGE_HEIGHT; y += gridStep) {
                g.drawLine(0, y, IMAGE_WIDTH, y);
            }

            String fontName = pickAvailableFont();

            // 3) 좌상단 timestamp + CCTV ID
            LocalDateTime ts = (capturedAt != null) ? capturedAt : LocalDateTime.now();
            g.setColor(new Color(255, 255, 255, 220));
            g.setFont(new Font(fontName, Font.BOLD, 36));
            g.drawString(safe(cctvName) + "  " + ts.format(TS_FMT), 60, 110);

            // 4) 중앙 메인 텍스트 — Frame #N
            String mainTop = "Frame #" + frameNo;
            String mainBottom = safe(cctvName) + "  ·  " + EVENT_KOR.getOrDefault(eventCode, eventCode);
            g.setFont(new Font(fontName, Font.BOLD, 140));
            drawCentered(g, mainTop, IMAGE_WIDTH / 2, IMAGE_HEIGHT / 2 - 80, Color.WHITE);
            g.setFont(new Font(fontName, Font.PLAIN, 72));
            drawCentered(g, mainBottom, IMAGE_WIDTH / 2, IMAGE_HEIGHT / 2 + 40, new Color(255, 255, 255, 240));

            // 5) 우하단 워터마크 — DEV PLACEHOLDER (운영 데이터로 오인 방지)
            g.setFont(new Font(fontName, Font.BOLD, 32));
            String watermark = "DEV PLACEHOLDER · NOT FOR DISTRIBUTION";
            int wmWidth = g.getFontMetrics().stringWidth(watermark);
            g.setColor(new Color(0, 0, 0, 130));
            g.fillRect(IMAGE_WIDTH - wmWidth - 80, IMAGE_HEIGHT - 80, wmWidth + 60, 50);
            g.setColor(new Color(255, 255, 255, 230));
            g.drawString(watermark, IMAGE_WIDTH - wmWidth - 50, IMAGE_HEIGHT - 45);

            // 6) 좌하단 보조 정보 — 1920x1080
            g.setFont(new Font(fontName, Font.PLAIN, 24));
            g.setColor(new Color(255, 255, 255, 180));
            g.drawString("1920 x 1080  ·  synthetic placeholder for ML labeling QA", 60, IMAGE_HEIGHT - 50);
        } finally {
            g.dispose();
        }

        // JPEG 저장 (품질 0.85 정도 — ImageIO 기본 사용)
        boolean ok = ImageIO.write(img, "jpg", target.toFile());
        if (!ok) {
            throw new IOException("ImageIO.write 실패 (writer 미지원): " + target);
        }
        return true;
    }

    private static void drawCentered(Graphics2D g, String text, int cx, int cy, Color color) {
        g.setColor(color);
        int w = g.getFontMetrics().stringWidth(text);
        int h = g.getFontMetrics().getAscent();
        g.drawString(text, cx - w / 2, cy + h / 2);
    }

    /** 시스템에 설치된 폰트 중 첫 번째로 매칭되는 후보 반환. */
    private static String pickAvailableFont() {
        Set<String> available = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : FONT_CANDIDATES) {
            if (available.contains(name)) {
                return name;
            }
        }
        return Font.SANS_SERIF;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
