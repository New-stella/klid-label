package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 소재 해제본을 읽는다 — 원장에 쓰지 않고 읽기만 한다(ADR-068).
 *
 * <h3>구성 — 개발망 실물로 확인했다(2026-09-16)</h3>
 * <p>해제본 아래에 프레임 이미지와 <b>같은 이름</b>의 라벨 문서가 짝으로 놓인다. 영상 폴더도 버전
 * 폴더도 비식별 이미지 폴더도 없었다:
 * <pre>
 *   content/0001.jpg  content/0001.json
 *   content/0002.jpg  content/0002.json
 * </pre>
 * <p>그래서 해제본을 <b>재귀</b>로 훑어 짝을 찾는다 — 평평한 자리와 하위 폴더 안이 섞여 있어도 같은
 * 규칙으로 읽힌다. <b>영상 식별자는 폴더 이름이 아니라 문서의 영상 파일명</b>({@code video.file_name})
 * 이며, 같은 값이면 한 영상으로 묶는다.
 *
 * <p>⚠ 구 구성(<code>{영상 키}/v{n}/deid/</code> 3단 · 버전이 여럿이면 최대 하나)은 <b>폐기</b>됐다.
 * 저작도구의 검수 승인 산출물 구조를 그대로 가정한 것이었고 실물은 그보다 단순하다. 되살리지 말 것.
 *
 * <h3>★ 구성이 어긋나면 추측으로 채우지 않는다 — 전부 읽고 나서 판정한다</h3>
 * <p>짝이 없거나, 문서를 읽을 수 없거나, 문서에 영상 파일명이 없으면 {@link LayoutMismatch} 로 멈춘다.
 * 이 판정은 <b>원장에 한 행도 쓰기 전에</b> 끝난다 — 영상 몇 건만 적재된 채 멈추면 「구성이 틀렸다」와
 * 「일부만 있다」가 구분되지 않는다.
 *
 * <h3>★ 원본 이미지 폴더를 읽지 않는다</h3>
 * <p>확인한 실물에는 원본 이미지가 들어 있지 않았으나(포털이 배포 구분으로 비식별본임을 표시한다)
 * 규칙은 남긴다 — 그런 자리가 들어오는 날 조용히 열리지 않게 한다. 이름이
 * {@value #ORIGINAL_DIR} 인 폴더는 <b>통째로 건너뛴다</b>(그 안의 짝도 읽지 않는다).
 *
 * <h3>경로 규칙 (INT-014 우리 쪽 규칙과 같은 축 · CWE-22/59)</h3>
 * <ul>
 *   <li>해제본 루트를 <b>실경로</b>로 고정하고 모든 파일이 그 하위인지 본다.</li>
 *   <li>해제본 안에 <b>심링크가 하나라도</b> 있으면 멈춘다. 해제기는 링크를 만들지 않으므로 링크가 있다는
 *       것은 해제본이 바뀌었다는 뜻이다.</li>
 *   <li>파일은 {@code NOFOLLOW_LINKS} 로 연다.</li>
 *   <li>영상 파일명은 <b>경로로 쓰지 않지만</b> 키로 삼기 전에 형식을 본다 — 경로 구분자·상위 참조·제어
 *       문자가 섞이면 멈춘다.</li>
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

    /** 원본 이미지 폴더 이름 — 이 이름의 폴더는 하위까지 읽지 않는다. */
    static final String ORIGINAL_DIR = "orgnl";

    /** 프레임 이미지 확장자(소문자). */
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg");

    /** 라벨 문서 확장자(소문자). */
    private static final String DOC_EXTENSION = "json";

    /** 이름 끝의 숫자 — 문서에 프레임 번호가 없을 때의 폴백 조달처. */
    static final Pattern NUMERIC_STEM = Pattern.compile("^(\\d{1,9})$");

    /** 영상 파일명으로 쓸 수 없는 글자 — 경로 구분자·제어 문자. */
    private static final Pattern VIDEO_KEY_FORBIDDEN = Pattern.compile("[/\\\\\\p{Cntrl}]");

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
        this.docReader = objectMapper.readerFor(PortalDatasetDoc.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 해제본 전체를 읽어 등록 계획의 재료를 만든다.
     *
     * @param contentDir 공개된 해제본의 {@code content} 자리
     * @throws LayoutMismatch 구성이 실물과 다를 때 — 이 경우 원장에 아무것도 쓰지 않아야 한다
     * @throws IOException    입출력 실패
     */
    public DatasetLayout read(Path contentDir) throws IOException {
        if (contentDir == null || !Files.isDirectory(contentDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.CONTENT_MISSING);
        }
        Path contentReal = contentDir.toRealPath(LinkOption.NOFOLLOW_LINKS);

        List<Pair> pairs = collectPairs(contentReal);
        if (pairs.isEmpty()) {
            // 짝이 하나도 없다 — 등록할 수 있는 영상이 없다.
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.NO_VIDEO);
        }

        // 영상 파일명 → 프레임. 문서를 읽는 순서는 경로 오름차순이라 회차마다 같다.
        Map<String, List<DatasetFrame>> framesByKey = new LinkedHashMap<>();
        Map<String, DatasetVideoMeta> metaByKey = new LinkedHashMap<>();
        for (Pair pair : pairs) {
            PortalDatasetDoc doc = parse(pair.doc());
            String videoKey = videoKeyOf(doc);
            DatasetFrame frame = toFrame(contentReal, pair, doc);
            List<DatasetFrame> frames = framesByKey.computeIfAbsent(videoKey, k -> new ArrayList<>());
            for (DatasetFrame seen : frames) {
                if (seen.frameNo() == frame.frameNo()) {
                    // 한 영상 안에 같은 프레임 번호가 둘이다 — 어느 쪽인지 고르지 않는다.
                    throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH);
                }
            }
            frames.add(frame);
            metaByKey.merge(videoKey, metaOf(doc), PortalDatasetLayoutReader::mergeMeta);
        }

        List<DatasetVideo> videos = new ArrayList<>(framesByKey.size());
        for (Map.Entry<String, List<DatasetFrame>> e : framesByKey.entrySet()) {
            List<DatasetFrame> frames = new ArrayList<>(e.getValue());
            frames.sort(Comparator.comparingLong(DatasetFrame::frameNo));
            videos.add(new DatasetVideo(e.getKey(), commonParent(contentReal, frames),
                    metaByKey.get(e.getKey()), List.copyOf(frames)));
        }
        videos.sort(Comparator.comparing(DatasetVideo::videoKey));
        // 구 구성에는 「비식별 이미지가 없어 건너뛴 영상」이 있었다. 지금 구성에는 그 자리가 없다 —
        // 표식 계약을 바꾸지 않으려고 칸만 남긴다(항상 0).
        return new DatasetLayout(List.copyOf(videos), 0);
    }

    // ------------------------------------------------------------------ 탐색

    /**
     * 해제본을 재귀로 훑어 <b>같은 폴더의 같은 이름</b>인 이미지·문서 짝을 모은다.
     *
     * <p>짝 규칙 밖의 파일은 등록 대상이 아니다(프레임으로 짐작하지 않는다). 다만 <b>한쪽만 있는</b>
     * 이미지·문서는 구성 불일치로 본다 — 조용히 빠지면 「일부만 등록됐다」가 드러나지 않는다.
     */
    private static List<Pair> collectPairs(Path contentReal) throws IOException {
        // (폴더, 이름) → 이미지 / 문서. 경로 오름차순으로 훑어 결과 순서를 고정한다.
        Map<Path, Path> images = new TreeMap<>();
        Map<Path, Path> docs = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(contentReal)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(p)) {
                    log.warn("[PortalDataset] 해제본 안에서 링크를 발견해 멈춥니다.");
                    throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.SYMLINK_REJECTED);
                }
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (isUnderOriginalDir(contentReal, p)) {
                    continue; // ★ 원본 이미지 폴더는 통째로 읽지 않는다.
                }
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot <= 0) {
                    continue;
                }
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                Path key = p.getParent().resolve(name.substring(0, dot));
                Map<Path, Path> target;
                if (IMAGE_EXTENSIONS.contains(ext)) {
                    target = images;
                } else if (DOC_EXTENSION.equals(ext)) {
                    target = docs;
                } else {
                    continue;
                }
                if (target.putIfAbsent(key, p) != null) {
                    // 0001.jpg 와 0001.jpeg 처럼 같은 이름이 둘이다 — 어느 쪽인지 고르지 않는다.
                    throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH);
                }
            }
        }
        if (!images.keySet().equals(docs.keySet())) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH);
        }
        List<Pair> pairs = new ArrayList<>(images.size());
        for (Map.Entry<Path, Path> e : images.entrySet()) {
            pairs.add(new Pair(e.getKey().getFileName().toString(), e.getValue(), docs.get(e.getKey())));
        }
        return pairs;
    }

    /** 경로가 원본 이미지 폴더의 하위인가(해제본 루트까지 올라가며 본다). */
    private static boolean isUnderOriginalDir(Path contentReal, Path file) {
        for (Path cur = file.getParent(); cur != null && !cur.equals(contentReal); cur = cur.getParent()) {
            if (cur.getFileName() != null && ORIGINAL_DIR.equals(cur.getFileName().toString())) {
                return true;
            }
        }
        return false;
    }

    /** 그 영상의 프레임들이 공유하는 가장 깊은 폴더 — 원천 위치로 기록한다. */
    private static Path commonParent(Path contentReal, List<DatasetFrame> frames) {
        Path common = frames.get(0).image().getParent();
        for (DatasetFrame f : frames) {
            Path dir = f.image().getParent();
            while (common != null && !dir.startsWith(common)) {
                common = common.getParent();
            }
            if (common == null || !common.startsWith(contentReal)) {
                return contentReal;
            }
        }
        return common;
    }

    // ------------------------------------------------------------------ 영상 키

    /**
     * 문서가 싣는 영상 파일명 — <b>이 값이 영상 식별자</b>다.
     *
     * <p>비어 있으면 짐작하지 않는다(파일 이름·폴더 이름으로 대신하지 않는다). 경로로 쓰지는 않지만
     * 경로 구분자·상위 참조가 섞인 값은 키로 삼지 않는다.
     */
    private static String videoKeyOf(PortalDatasetDoc doc) {
        String raw = doc.video() == null ? null : doc.video().resolvedFileName();
        if (raw == null || raw.isBlank()) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING);
        }
        String key = raw.trim();
        if (VIDEO_KEY_FORBIDDEN.matcher(key).find() || ".".equals(key) || "..".equals(key)) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VIDEO_KEY);
        }
        return key;
    }

    // ------------------------------------------------------------------ 파일 읽기

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

    private PortalDatasetDoc parse(Path doc) {
        try (InputStream in = Files.newInputStream(doc, LinkOption.NOFOLLOW_LINKS)) {
            PortalDatasetDoc parsed = docReader.readValue(in);
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

    // ------------------------------------------------------------------ 값 추리기

    /** 문서 한 장에서 적재에 필요한 값만 추린다. */
    private DatasetFrame toFrame(Path contentReal, Pair pair, PortalDatasetDoc doc) throws IOException {
        PortalDatasetDoc.Image img = doc.image();
        String description = img == null ? null : blankToNull(img.description());
        if (description != null && description.length() > FRAME_DESCRIPTION_MAX) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }

        long frameNo = frameNoOf(pair.stem(), img);
        // 영상 안 프레임 번호는 산출물 모양에만 있다 — 없으면 비운다(프레임 번호로 대신하지 않는다).
        Long videoFrameNo = (img == null || img.frameNum() == null) ? null : img.frameNum().longValue();
        if (videoFrameNo != null && videoFrameNo < 0) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }

        Map<String, String> categoryNames = new HashMap<>();
        if (doc.categories() != null) {
            for (PortalDatasetDoc.Category c : doc.categories()) {
                if (c != null && c.id() != null) {
                    categoryNames.putIfAbsent(c.id(), c.name());
                }
            }
        }

        List<DatasetShape> shapes = new ArrayList<>();
        int skippedShapes = 0;
        if (doc.annotations() != null) {
            for (PortalDatasetDoc.Annotation a : doc.annotations()) {
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

        Path image = requireWithin(contentReal, pair.image());
        requireJpeg(image);
        return new DatasetFrame(
                frameNo, image, videoFrameNo, description,
                img == null ? null : yn(img.anonymity()),
                img == null ? null : yn(img.pseudonymity()),
                img == null ? null : yn(img.privacyIncluded()),
                List.copyOf(shapes), skippedShapes);
    }

    /**
     * 프레임 번호 — 문서의 프레임 번호가 1순위, 없으면 파일 이름의 숫자.
     *
     * <p>둘 다 없으면 짐작하지 않는다. 음수는 파일 이름 규칙을 깨므로 거부한다.
     */
    private static long frameNoOf(String stem, PortalDatasetDoc.Image img) {
        Integer fromDoc = img == null ? null : img.frameNo();
        if (fromDoc != null) {
            if (fromDoc < 0) {
                throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
            }
            return fromDoc.longValue();
        }
        Matcher m = NUMERIC_STEM.matcher(stem);
        if (!m.matches()) {
            throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
        }
        return Long.parseLong(m.group(1));
    }

    /**
     * 어노테이션 한 건 → 포털 도형. 키포인트·좌표 없는 항목은 {@code null}(건너뜀).
     *
     * <p>좌표 형식이 어긋나면 고쳐 넣지 않고 멈춘다.
     */
    private DatasetShape toShape(PortalDatasetDoc.Annotation a, Map<String, String> categoryNames) {
        String type;
        List<Point> points;
        if (a.polygon() != null && !a.polygon().isEmpty()) {
            type = LsDataLbl.TYPE_POLYGON;
            points = polygonPoints(a.polygon());
        } else if (a.bbox() != null && !a.bbox().isEmpty()) {
            if (!PortalDatasetDoc.isXywh(a.bboxFormat())) {
                // 좌표 뜻이 다르다 — 어느 뜻인지 짐작해 읽지 않는다.
                throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
            }
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
        // 이름 조달: 산출물 모양이면 분류 목록에서, 실물 모양이면 어노테이션의 분류 이름에서.
        // ★ 어느 쪽이든 <이름으로 마스터를 되짚지 않는다> — 이름에 유일성 제약이 없다.
        String name = categoryId == null
                ? blankToNull(a.category())
                : blankToNull(categoryNames.get(categoryId));
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

    /** 영상 단위 메타 한 장. */
    private static DatasetVideoMeta metaOf(PortalDatasetDoc doc) {
        PortalDatasetDoc.Video v = doc.video();
        if (v == null) {
            return new DatasetVideoMeta(null, null, null, null, null, null);
        }
        return new DatasetVideoMeta(blankToNull(v.resolvedFileName()), blankToNull(v.fps()),
                v.width(), v.height(), v.vdoLenSec(), blankToNull(v.resolvedEventTypeCd()));
    }

    /** 문서마다 같은 영상 블록이 실리므로 <b>먼저 나온 값</b>을 쓴다(빈 칸만 뒤 문서가 채운다). */
    private static DatasetVideoMeta mergeMeta(DatasetVideoMeta a, DatasetVideoMeta b) {
        return new DatasetVideoMeta(
                a.originalFilename() != null ? a.originalFilename() : b.originalFilename(),
                a.fps() != null ? a.fps() : b.fps(),
                a.width() != null ? a.width() : b.width(),
                a.height() != null ? a.height() : b.height(),
                a.lengthSec() != null ? a.lengthSec() : b.lengthSec(),
                a.eventTypeCd() != null ? a.eventTypeCd() : b.eventTypeCd());
    }

    // ------------------------------------------------------------------ 결과 값

    /** 이미지·문서 짝 한 건. */
    private record Pair(String stem, Path image, Path doc) {
    }

    /**
     * 해제본 전체.
     *
     * @param skippedVideos 구 구성의 잔재 — 지금은 항상 0이며 표식 계약을 지키려고 남긴다
     */
    public record DatasetLayout(List<DatasetVideo> videos, int skippedVideos) {
    }

    /**
     * 영상 한 건.
     *
     * @param videoKey  문서의 영상 파일명 — <b>폴더 이름이 아니다</b>
     * @param videoDir  그 영상의 프레임들이 공유하는 가장 깊은 폴더의 실경로 — 원천 위치 기록용
     *                  (로그·응답에 싣지 않는다)
     * @param meta      영상 단위 메타
     * @param frames    프레임(번호 오름차순)
     */
    public record DatasetVideo(String videoKey, Path videoDir, DatasetVideoMeta meta, List<DatasetFrame> frames) {
    }

    /** 영상 단위 메타 — 없는 값은 {@code null}. */
    public record DatasetVideoMeta(String originalFilename, String fps, Integer width, Integer height,
                                   Integer lengthSec, String eventTypeCd) {
    }

    /**
     * 프레임 한 장 — 이미지 실경로와 문서에서 추린 값.
     *
     * @param skippedShapes 옮기지 않은 어노테이션 수(키포인트 등)
     */
    public record DatasetFrame(long frameNo, Path image, Long videoFrameNo, String description,
                               String anonymity, String pseudonymity, String privacyIncluded,
                               List<DatasetShape> shapes, int skippedShapes) {
    }

    /**
     * 옮길 도형 한 건.
     *
     * @param labelId 문서 분류 식별자를 숫자로 읽은 값 — <b>활성 마스터 확인 전</b>이다. 실물처럼 분류가
     *                이름 문자열뿐이면 {@code null} 이며 마스터에 잇지 않는다
     */
    public record DatasetShape(String lblTypeCd, String pointsJson, Long labelId, String labelName,
                               String trackId) {
    }

    /** 구성이 실물과 다르다 — 사유를 값으로 싣는다. */
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
