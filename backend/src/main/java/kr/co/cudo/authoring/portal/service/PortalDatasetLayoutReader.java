package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.dataset.export.json.NiaAnnotation;
import kr.co.cudo.authoring.dataset.export.json.NiaAnnotationDoc;
import kr.co.cudo.authoring.dataset.export.json.NiaCategory;
import kr.co.cudo.authoring.dataset.export.json.NiaImage;
import kr.co.cudo.authoring.dataset.export.json.NiaVideo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 소재 해제본을 <b>가정한 구성</b>으로 읽는다 — 원장에 쓰지 않고 읽기만 한다(ADR-068).
 *
 * <h3>★ 구성은 확인된 사실이 아니라 가정이다</h3>
 * <p>배포 압축본 샘플을 받지 못했다. 저작도구의 검수 승인 산출물 구조로 가정한다:
 * <pre>
 *   content/ … /{영상 키}/v{n}/deid/NNNN.jpg   ← 비식별 프레임 이미지
 *   content/ … /{영상 키}/v{n}/deid/NNNN.json  ← 같은 이름의 NIA 어노테이션 문서
 *   content/ … /{영상 키}/v{n}/orgnl/…         ← 원본 — <b>읽지 않는다</b>
 * </pre>
 * <p>영상 키는 버전 폴더 바로 위 폴더 이름이다. 한 영상에 버전이 여럿이면 <b>번호가 가장 큰 하나</b>만 쓴다.
 * 샘플을 받으면 이 클래스만 고친다.
 *
 * <h3>★ 구성이 어긋나면 추측으로 채우지 않는다 — 전부 읽고 나서 판정한다</h3>
 * <p>짝이 없거나, 문서를 읽을 수 없거나, 영상이 한 건도 없으면 {@link LayoutMismatch} 로 멈춘다. 이 판정은
 * <b>원장에 한 행도 쓰기 전에</b> 끝난다 — 영상 몇 건만 적재된 채 멈추면 「구성이 틀렸다」와 「일부만
 * 있다」가 구분되지 않는다. 그래서 전 영상의 문서를 먼저 읽어 적재에 필요한 값만 추려 낸다.
 *
 * <h3>★ 원본 이미지 폴더를 읽지 않는다</h3>
 * <p>포털 사용자에게 원본(비식별 이전) 이미지를 노출하지 않는다. 이 클래스가 여는 파일은 {@code deid/}
 * 바로 아래의 이미지와 문서뿐이다.
 *
 * <h3>경로 규칙 (INT-014 우리 쪽 규칙과 같은 축 · CWE-22/59)</h3>
 * <ul>
 *   <li>해제본 루트를 <b>실경로</b>로 고정하고 모든 파일이 그 하위인지 본다.</li>
 *   <li>해제본 안에 <b>심링크가 하나라도</b> 있으면 멈춘다. 해제기는 링크를 만들지 않으므로 링크가 있다는
 *       것은 해제본이 바뀌었다는 뜻이다.</li>
 *   <li>파일은 {@code NOFOLLOW_LINKS} 로 연다.</li>
 * </ul>
 *
 * <h3>포털 도형만 옮긴다</h3>
 * <p>사각 박스·폴리곤만 옮기고 키포인트는 건너뛴다 — 포털 수동 라벨링이 그 두 형태만 제공한다(ADR-013).
 *
 * <p>경로·파일명·문서 원문을 로그에 남기지 않는다(CWE-209/117).
 *
 * @design ADR-068
 * @design AC-1118
 */
@Slf4j
@Component
public class PortalDatasetLayoutReader {

    /** 버전 폴더 이름 — {@code v{n}}. */
    static final Pattern VERSION_DIR = Pattern.compile("^v(\\d{1,9})$");

    /** 비식별 이미지 폴더 이름. */
    static final String DEID_DIR = "deid";

    /** 프레임 파일 이름 — {@code NNNN.jpg} / {@code NNNN.json}. */
    static final Pattern FRAME_FILE = Pattern.compile("^(\\d{1,9})\\.(jpg|json)$");

    /** {@code LS_DATA_SRC.FRM_EXPLN} 컬럼 폭. */
    static final int FRAME_DESCRIPTION_MAX = 1000;

    /** {@code LS_DATA_LBL.LBL_NM} 컬럼 폭 — 외부 이관 적재와 같은 규칙으로 자른다(표시 이름이다). */
    static final int LABEL_NAME_MAX = 80;

    /** {@code LS_DATA_LBL.TRCK_ID} 컬럼 폭 — 넘으면 자르지 않는다(잘리면 서로 다른 트랙이 합쳐진다). */
    static final int TRACK_ID_MAX = 30;

    private static final int IMAGE_HEAD_BYTES = ImageMagicByteValidator.HEADER_BYTES;

    private final ObjectReader docReader;
    private final ObjectMapper objectMapper;

    public PortalDatasetLayoutReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 문서에 모르는 키가 섞여도 읽는다 — 우리가 쓰는 칸만 추린다. 칸의 <형식>이 어긋나면 실패다.
        this.docReader = objectMapper.readerFor(NiaAnnotationDoc.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 해제본 전체를 읽어 등록 계획의 재료를 만든다.
     *
     * @param contentDir 공개된 해제본의 {@code content} 자리
     * @throws LayoutMismatch 구성이 가정과 다를 때 — 이 경우 원장에 아무것도 쓰지 않아야 한다
     * @throws IOException    입출력 실패
     */
    public DatasetLayout read(Path contentDir) throws IOException {
        if (contentDir == null || !Files.isDirectory(contentDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.CONTENT_MISSING);
        }
        Path contentReal = contentDir.toRealPath(LinkOption.NOFOLLOW_LINKS);

        // 영상 키 → (버전 번호 → 버전 폴더). 같은 키가 다른 부모 아래 또 있으면 추측하지 않는다.
        Map<String, TreeMap<Long, Path>> versionsByKey = new TreeMap<>();
        Map<String, Path> videoDirByKey = new HashMap<>();
        try (Stream<Path> walk = Files.walk(contentReal)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(p)) {
                    log.warn("[PortalDataset] 해제본 안에서 링크를 발견해 멈춥니다.");
                    throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.SYMLINK_REJECTED);
                }
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || p.equals(contentReal)) {
                    continue;
                }
                Matcher m = VERSION_DIR.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                Path videoDir = p.getParent();
                if (videoDir == null || videoDir.equals(contentReal)) {
                    // 영상 폴더 없이 버전 폴더가 해제본 바로 아래 있다 — 영상 키를 정할 수 없다.
                    throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.NO_VIDEO);
                }
                if (isInsideVersionTree(contentReal, videoDir)) {
                    // 버전 폴더 안에 또 버전 폴더가 있다(예: v1/deid/v2) — 가정한 구성이 아니다.
                    continue;
                }
                String videoKey = videoDir.getFileName().toString();
                Path known = videoDirByKey.putIfAbsent(videoKey, videoDir);
                if (known != null && !known.equals(videoDir)) {
                    throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.AMBIGUOUS_VIDEO_KEY);
                }
                versionsByKey.computeIfAbsent(videoKey, k -> new TreeMap<>())
                        .put(Long.parseLong(m.group(1)), p);
            }
        }

        List<DatasetVideo> videos = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, TreeMap<Long, Path>> e : versionsByKey.entrySet()) {
            Path versionDir = e.getValue().lastEntry().getValue();
            Path deidDir = versionDir.resolve(DEID_DIR);
            if (!Files.isDirectory(deidDir, LinkOption.NOFOLLOW_LINKS)) {
                // 비식별 이미지가 없는 영상은 등록하지 않는다 — 원본 폴더로 대신하지 않는다.
                skipped++;
                continue;
            }
            List<DatasetFrame> frames = readFrames(contentReal, deidDir);
            if (frames.isEmpty()) {
                skipped++;
                continue;
            }
            videos.add(new DatasetVideo(e.getKey(), videoDirByKey.get(e.getKey()), videoMeta(frames), frames));
        }
        if (videos.isEmpty()) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.NO_VIDEO);
        }
        return new DatasetLayout(List.copyOf(videos), skipped);
    }

    /** 경로가 어떤 버전 폴더의 하위인가(해제본 루트까지 올라가며 본다). */
    private static boolean isInsideVersionTree(Path contentReal, Path dir) {
        for (Path cur = dir; cur != null && !cur.equals(contentReal); cur = cur.getParent()) {
            if (cur.getFileName() != null && VERSION_DIR.matcher(cur.getFileName().toString()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 비식별 이미지 폴더 한 곳의 프레임을 읽는다 — 짝·형식이 어긋나면 멈춘다. */
    private List<DatasetFrame> readFrames(Path contentReal, Path deidDir) throws IOException {
        Map<Long, Path> images = new TreeMap<>();
        Map<Long, Path> docs = new TreeMap<>();
        try (Stream<Path> list = Files.list(deidDir)) {
            for (Path f : (Iterable<Path>) list::iterator) {
                if (Files.isSymbolicLink(f)) {
                    throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.SYMLINK_REJECTED);
                }
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Matcher m = FRAME_FILE.matcher(f.getFileName().toString());
                if (!m.matches()) {
                    // 짝 규칙 밖의 파일은 등록 대상이 아니다(프레임으로 짐작하지 않는다).
                    continue;
                }
                long frameNo = Long.parseLong(m.group(1));
                Map<Long, Path> target = "jpg".equals(m.group(2)) ? images : docs;
                if (target.putIfAbsent(frameNo, f) != null) {
                    // 0001.jpg 와 1.jpg 처럼 같은 번호가 둘이다 — 어느 쪽인지 고르지 않는다.
                    throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH);
                }
            }
        }
        if (!images.keySet().equals(docs.keySet())) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH);
        }

        List<DatasetFrame> frames = new ArrayList<>(images.size());
        for (Map.Entry<Long, Path> e : images.entrySet()) {
            Path image = requireWithin(contentReal, e.getValue());
            Path doc = requireWithin(contentReal, docs.get(e.getKey()));
            requireJpeg(image);
            frames.add(toFrame(e.getKey(), image, parse(doc)));
        }
        return frames;
    }

    /** 실경로가 해제본 루트 하위인지 확인한다 — 아니면 링크 우회로 본다. */
    private static Path requireWithin(Path contentReal, Path file) throws IOException {
        Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(contentReal)) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.SYMLINK_REJECTED);
        }
        return real;
    }

    private static void requireJpeg(Path image) throws IOException {
        byte[] head = new byte[IMAGE_HEAD_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(image, LinkOption.NOFOLLOW_LINKS)) {
            read = in.readNBytes(head, 0, head.length);
        }
        byte[] actual = read == head.length ? head : java.util.Arrays.copyOf(head, read);
        Optional<ImageMagicByteValidator.ImageFormat> format = ImageMagicByteValidator.detect(actual);
        if (format.isEmpty() || format.get() != ImageMagicByteValidator.ImageFormat.JPEG) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.IMAGE_NOT_JPEG);
        }
    }

    private NiaAnnotationDoc parse(Path doc) {
        try (InputStream in = Files.newInputStream(doc, LinkOption.NOFOLLOW_LINKS)) {
            NiaAnnotationDoc parsed = docReader.readValue(in);
            if (parsed == null) {
                throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.DOCUMENT_UNREADABLE);
            }
            return parsed;
        } catch (IOException | RuntimeException e) {
            if (e instanceof LayoutMismatch lm) {
                throw lm;
            }
            // 문서 원문·예외 메시지를 싣지 않는다 — 형식이 어긋났다는 사실만.
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.DOCUMENT_UNREADABLE);
        }
    }

    /** 문서 한 장에서 적재에 필요한 값만 추린다. */
    private DatasetFrame toFrame(long frameNo, Path image, NiaAnnotationDoc doc) {
        NiaImage img = doc.image();
        String description = img == null ? null : blankToNull(img.description());
        if (description != null && description.length() > FRAME_DESCRIPTION_MAX) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }
        Long videoFrameNo = (img == null || img.frameNum() == null) ? null : img.frameNum().longValue();
        if (videoFrameNo != null && videoFrameNo < 0) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }

        Map<String, String> categoryNames = new HashMap<>();
        if (doc.categories() != null) {
            for (NiaCategory c : doc.categories()) {
                if (c != null && c.id() != null) {
                    categoryNames.putIfAbsent(c.id(), c.name());
                }
            }
        }

        List<DatasetShape> shapes = new ArrayList<>();
        int skippedShapes = 0;
        if (doc.annotations() != null) {
            for (NiaAnnotation a : doc.annotations()) {
                if (a == null) {
                    continue;
                }
                DatasetShape shape = toShape(a, categoryNames);
                if (shape == null) {
                    skippedShapes++;
                } else {
                    shapes.add(shape);
                }
            }
        }

        NiaVideo v = doc.video();
        return new DatasetFrame(
                frameNo, image, videoFrameNo, description,
                img == null ? null : yn(img.anonymity()),
                img == null ? null : yn(img.pseudonymity()),
                img == null ? null : yn(img.privacyIncluded()),
                List.copyOf(shapes), skippedShapes,
                v == null ? null : blankToNull(v.filename()),
                v == null ? null : blankToNull(v.fps()),
                v == null ? null : v.width(),
                v == null ? null : v.height());
    }

    /**
     * 어노테이션 한 건 → 포털 도형. 키포인트·좌표 없는 항목은 {@code null}(건너뜀).
     *
     * <p>좌표 형식이 어긋나면 고쳐 넣지 않고 멈춘다.
     */
    private DatasetShape toShape(NiaAnnotation a, Map<String, String> categoryNames) {
        String type;
        List<Point> points;
        if (a.polygon() != null && !a.polygon().isEmpty()) {
            type = LsDataLbl.TYPE_POLYGON;
            points = polygonPoints(a.polygon());
        } else if (a.bbox() != null && !a.bbox().isEmpty()) {
            type = LsDataLbl.TYPE_BBOX;
            points = bboxPoints(a.bbox());
        } else {
            // 키포인트(포털 미제공)거나 좌표가 없다 — 옮기지 않는다.
            return null;
        }

        String trackId = blankToNull(a.trackId());
        if (trackId != null && trackId.length() > TRACK_ID_MAX) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }
        String categoryId = blankToNull(a.categoryId());
        Long labelId = parseLabelId(categoryId);
        String name = categoryId == null ? null : blankToNull(categoryNames.get(categoryId));
        if (name != null && name.length() > LABEL_NAME_MAX) {
            name = name.substring(0, LABEL_NAME_MAX);
        }
        return new DatasetShape(type, LabelPointSerializer.toJson(points, objectMapper), labelId, name, trackId);
    }

    /** {@code [x, y, w, h]} → 대각 두 점. */
    private static List<Point> bboxPoints(List<Number> bbox) {
        if (bbox.size() != 4) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }
        double x = finite(bbox.get(0));
        double y = finite(bbox.get(1));
        double w = finite(bbox.get(2));
        double h = finite(bbox.get(3));
        if (w < 0 || h < 0) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }
        return List.of(new Point(x, y), new Point(x + w, y + h));
    }

    /** {@code [[x1,y1,x2,y2,…]]} → 점 목록. 고리는 하나여야 한다(산출물이 한 고리로 쓴다). */
    private static List<Point> polygonPoints(List<List<Number>> rings) {
        if (rings.size() != 1 || rings.get(0) == null) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }
        List<Number> flat = rings.get(0);
        if (flat.size() % 2 != 0 || flat.size() < 6) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }
        List<Point> points = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i < flat.size(); i += 2) {
            points.add(new Point(finite(flat.get(i)), finite(flat.get(i + 1))));
        }
        return points;
    }

    private static double finite(Number n) {
        if (n == null || !Double.isFinite(n.doubleValue())) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }
        return n.doubleValue();
    }

    /** 숫자 식별자만 라벨 마스터 후보로 본다 — 실재·활성 확인은 적재 단계가 한다. 이름으로 역매핑하지 않는다. */
    private static Long parseLabelId(String categoryId) {
        if (categoryId == null || !categoryId.chars().allMatch(Character::isDigit) || categoryId.length() > 18) {
            return null;
        }
        return Long.parseLong(categoryId);
    }

    /** {@code Y}/{@code N} 만 옮긴다 — 그 밖의 표기는 비워 적재 기본값에 맡긴다(짐작해 접지 않는다). */
    private static String yn(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        return ("Y".equals(v) || "N".equals(v)) ? v : null;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** 영상 단위 메타 — 문서마다 같은 영상 블록이 실리므로 첫 값을 쓴다. */
    private static DatasetVideoMeta videoMeta(List<DatasetFrame> frames) {
        String filename = null;
        String fps = null;
        Integer width = null;
        Integer height = null;
        for (DatasetFrame f : frames) {
            if (filename == null) filename = f.videoFilename();
            if (fps == null) fps = f.videoFps();
            if (width == null) width = f.videoWidth();
            if (height == null) height = f.videoHeight();
        }
        return new DatasetVideoMeta(filename, fps, width, height);
    }

    // ------------------------------------------------------------------ 결과 값

    /** 해제본 전체. @param skippedVideos 비식별 이미지가 없어 등록하지 않는 영상 수 */
    public record DatasetLayout(List<DatasetVideo> videos, int skippedVideos) {
    }

    /**
     * 영상 한 건.
     *
     * @param videoKey  버전 폴더 바로 위 폴더 이름
     * @param videoDir  그 영상 폴더의 실경로 — 원천 위치 기록용(로그·응답에 싣지 않는다)
     * @param meta      영상 단위 메타
     * @param frames    프레임(번호 오름차순)
     */
    public record DatasetVideo(String videoKey, Path videoDir, DatasetVideoMeta meta, List<DatasetFrame> frames) {
    }

    /** 영상 단위 메타 — 없는 값은 {@code null}. */
    public record DatasetVideoMeta(String originalFilename, String fps, Integer width, Integer height) {
    }

    /**
     * 프레임 한 장 — 이미지 실경로와 문서에서 추린 값.
     *
     * @param skippedShapes 옮기지 않은 어노테이션 수(키포인트 등)
     */
    public record DatasetFrame(long frameNo, Path image, Long videoFrameNo, String description,
                               String anonymity, String pseudonymity, String privacyIncluded,
                               List<DatasetShape> shapes, int skippedShapes,
                               String videoFilename, String videoFps, Integer videoWidth, Integer videoHeight) {
    }

    /**
     * 옮길 도형 한 건.
     *
     * @param labelId 문서 분류 식별자를 숫자로 읽은 값 — <b>활성 마스터 확인 전</b>이다
     */
    public record DatasetShape(String lblTypeCd, String pointsJson, Long labelId, String labelName,
                               String trackId) {
    }

    /** 구성이 가정과 다르다 — 사유를 값으로 싣는다. */
    public static class LayoutMismatch extends RuntimeException {

        private final PortalDatasetRegistrationFailureReason reason;

        public LayoutMismatch(PortalDatasetRegistrationFailureReason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public PortalDatasetRegistrationFailureReason reason() {
            return reason;
        }
    }
}
