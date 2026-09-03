package kr.co.cudo.authoring.transfer.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.transfer.ImportPathPolicy;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 마킹 문서를 읽어 {@link MarkingDocument} 로 옮기는 단일 지점.
 *
 * <h3>문서의 모양</h3>
 * <p>최상위가 <b>이벤트 구간의 배열</b>이고, 구간마다 자기가 가리키는 영상의 이름과 시작·끝 지점,
 * 그리고 그 구간에서 뽑아 둔 시점 목록을 담는다. 표본은 구간이 하나뿐이지만 <b>여럿일 수 있으며</b>,
 * 그때는 구간들의 시점을 합쳐 하나의 마킹으로 만든다(구간마다 마킹을 나누면 영상당 활성 마킹이
 * 하나라는 제약에 걸린다).
 *
 * <h3>영상 이름은 <b>이름 항목</b>에서 얻고 경로 항목은 보관만 한다</h3>
 * <p>문서가 함께 담은 경로는 다른 체계에서 만들어진 절대 경로다(표본은 윈도우 경로). 그 값을 위치로
 * 쓰면 우리 저장소 밖을 가리키고, 조립에 넣으면 경로 순회가 된다(CWE-22). 짝짓기는 <b>이름</b>으로만
 * 하고 경로 원문은 기록으로만 남긴다(SEQ-030). 이름 항목이 비었을 때만 경로 원문에서
 * <b>마지막 이름 조각</b>을 꺼내 쓴다 — 그 경우에도 위치로 쓰는 것이 아니라 이름을 얻는 것뿐이다.
 *
 * <h3>비고 항목을 이벤트 유형으로 읽지 않는다</h3>
 * <p>표본 값이 「이벤트」라는 일반 문장이다. 유형은 사람이 화면에서 지정하며 여기서 파싱하면
 * <b>짐작이 곧 저장된 사실</b>이 된다.
 *
 * <h3>읽기에 상한을 둔다 (CWE-770)</h3>
 * <p>문서는 외부가 준 파일이라 크기를 우리가 정하지 않는다. 파일 크기·구간 수·시점 수에 상한을 두고,
 * 상한을 넘으면 <b>조용히 자르지 않고</b> 그 문서를 읽을 수 없는 것으로 다룬다 — 잘라 담으면 마킹이
 * 실제보다 적은 채로 적재되어 이벤트 구간의 일부가 영영 사라진다.
 *
 * @design DOMAIN-017
 * @design ADR-052
 * @design ADR-053
 * @design API-216
 * @design SEQ-030
 */
@Component
@RequiredArgsConstructor
public class MarkingDocumentParser {

    /** 마킹 문서 한 건의 크기 상한(byte) — 넘으면 읽지 않는다. */
    public static final long MAX_DOCUMENT_BYTES = 16L * 1024 * 1024;

    /** 한 문서가 담을 수 있는 이벤트 구간 수 상한. */
    public static final int MAX_SEGMENTS = 10_000;

    /** 한 문서가 담을 수 있는 시점 수 상한 — 정리하기 <b>전</b> 원소 수 기준이다. */
    public static final int MAX_MARKS = 100_000;

    /**
     * 역산 프레임 재생 속도의 소수 자릿수.
     *
     * <p>나눗셈 결과를 그대로 실으면 부동소수 꼬리가 응답과 로그에 그대로 드러나고, 같은 문서를
     * 두 번 읽었을 때 표시가 미세하게 달라 보인다. 자리를 고정해 계약을 안정시킨다.
     */
    private static final int FPS_SCALE = 3;

    /** 타임스탬프 문자열의 분 자리 상한 — {@link MarkItem} 이 받는 형식이 네 자리까지다. */
    private static final int MAX_TIMESTAMP_MINUTES = 9999;

    private final ObjectMapper objectMapper;

    /**
     * 마킹 문서 한 건을 읽는다.
     *
     * @param document 훑기가 판정한 <b>실경로</b>(표기 경로를 다시 만들지 않는다 — CWE-367)
     * @return 읽어 낸 결과. 읽을 수 없으면 내용이 빈 결과에 사유 경고가 담긴다(예외를 던지지 않는다 —
     * 문서 하나 때문에 묶음 전체가 멈추면 안 된다)
     */
    public MarkingDocument parse(Path document) {
        JsonNode root;
        try {
            if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
                return unreadable("마킹 문서가 일반 파일이 아니다.");
            }
            if (Files.size(document) > MAX_DOCUMENT_BYTES) {
                return unreadable("마킹 문서가 한 번에 읽을 수 있는 크기를 넘는다.");
            }
            try (InputStream in = Files.newInputStream(document, LinkOption.NOFOLLOW_LINKS)) {
                root = objectMapper.readTree(in);
            }
        } catch (IOException | RuntimeException e) {
            // CWE-209 — 내부 경로·파싱 원문을 설명에 담지 않는다.
            return unreadable("마킹 문서를 읽을 수 없다.");
        }
        return read(root);
    }

    /** 이미 읽어 둔 문서 본문을 옮긴다 — 파일 접근 없이 검증할 수 있게 열어 둔다. */
    public MarkingDocument read(JsonNode root) {
        if (root == null || !root.isArray()) {
            return unreadable("마킹 문서의 최상위가 이벤트 구간 목록이 아니다.");
        }
        if (root.size() > MAX_SEGMENTS) {
            return unreadable("마킹 문서의 이벤트 구간 수가 한 번에 읽을 수 있는 상한을 넘는다.");
        }

        List<MarkingWarning> warnings = new ArrayList<>();
        String videoNameRaw = null;
        String videoPathRaw = null;
        // 프레임 번호 -> 초. 같은 번호가 여러 구간에 나오면 처음 본 값을 쓴다(중복 제거).
        Map<Integer, Double> points = new LinkedHashMap<>();
        int rawMarkCount = 0;

        for (JsonNode segment : root) {
            if (segment == null || !segment.isObject()) {
                continue;
            }
            if (videoNameRaw == null) {
                videoNameRaw = textOrNull(segment.get("video_name"));
            }
            if (videoPathRaw == null) {
                videoPathRaw = textOrNull(segment.get("video_path"));
            }
            JsonNode images = segment.get("images");
            if (images == null || !images.isArray()) {
                continue;
            }
            for (JsonNode image : images) {
                if (image == null || !image.isObject()) {
                    continue;
                }
                if (++rawMarkCount > MAX_MARKS) {
                    return unreadable("마킹 문서의 시점 수가 한 번에 읽을 수 있는 상한을 넘는다.");
                }
                Integer frame = intOrNull(image.get("frame"));
                if (frame == null || frame < 0) {
                    // 프레임 번호가 없거나 음수면 그 시점을 놓을 자리가 없다 — 그 한 건만 버린다.
                    continue;
                }
                Double seconds = parseSeconds(textOrNull(image.get("time")));
                points.putIfAbsent(frame, seconds);
            }
        }

        String videoFileName = resolveVideoFileName(videoNameRaw, videoPathRaw);
        if (videoFileName == null) {
            warnings.add(MarkingWarning.of(MarkingImportWarningCode.UNUSABLE_VIDEO_FILE_NAME,
                    "마킹 문서에 짝지을 영상 파일 이름이 없다."));
        }
        String clipId = clipIdOf(videoFileName);
        if (videoFileName != null && clipId == null) {
            warnings.add(MarkingWarning.of(MarkingImportWarningCode.UNUSABLE_CLIP_ID,
                    "영상 파일 이름에서 영상 식별자를 만들 수 없다."));
        }

        List<Integer> frames = new ArrayList<>(points.keySet());
        frames.sort(Integer::compareTo);
        List<MarkItem> marks = new ArrayList<>(frames.size());
        for (Integer frame : frames) {
            marks.add(new MarkItem(frame, timestampOf(points.get(frame))));
        }
        if (marks.isEmpty()) {
            warnings.add(MarkingWarning.of(MarkingImportWarningCode.NO_MARK_FOUND,
                    "마킹 문서에 시점이 하나도 없다."));
        }

        Double declaredFps = deriveFps(frames, points);
        if (declaredFps == null && !marks.isEmpty()) {
            warnings.add(MarkingWarning.of(MarkingImportWarningCode.DECLARED_FPS_UNAVAILABLE,
                    "마킹 문서의 시점만으로는 프레임 재생 속도를 역산할 수 없다."));
        }

        return new MarkingDocument(videoFileName, videoPathRaw, clipId, root.size(),
                marks, declaredFps, warnings);
    }

    // ------------------------------------------------------------------ 영상 이름·식별자

    /**
     * 짝짓기에 쓸 영상 파일 이름을 정한다 — 이름 항목이 먼저이고 경로 원문은 <b>이름을 얻는 용도</b>로만
     * 물러선다.
     */
    private static String resolveVideoFileName(String videoNameRaw, String videoPathRaw) {
        String fromName = ExternalNameSanitizer.fileName(videoNameRaw, ImportPathPolicy.FILE_NAME_MAX);
        if (fromName != null) {
            return fromName;
        }
        return ExternalNameSanitizer.fileName(videoPathRaw, ImportPathPolicy.FILE_NAME_MAX);
    }

    /**
     * 영상 파일 이름에서 확장자를 뗀 값 — 적재 시 영상 식별자가 된다.
     *
     * <p>이 값은 <b>중복 반입을 막는 유일 제약의 키</b>이자 파일이 놓이는 디렉터리 이름이다. 그래서
     * 폭을 넘으면 잘라 담지 않고 <b>쓸 수 없다</b>고 답한다 — 자르면 서로 다른 영상이 같은 식별자가
     * 되어 뒤에 온 영상이 「이미 있음」으로 조용히 건너뛰어진다.
     */
    public static String clipIdOf(String videoFileName) {
        if (videoFileName == null || videoFileName.isBlank()) {
            return null;
        }
        int dot = videoFileName.lastIndexOf('.');
        String stem = (dot <= 0) ? videoFileName : videoFileName.substring(0, dot);
        stem = stem.trim();
        if (stem.isEmpty() || ".".equals(stem) || "..".equals(stem)) {
            return null;
        }
        // 이 값이 디렉터리 이름이 되므로 구분자가 섞이면 안 된다. 정제기가 이미 마지막 조각만
        // 남기지만, 정제기를 거치지 않은 값이 들어오는 경로가 생겨도 여기서 막힌다(CWE-22).
        if (stem.indexOf('/') >= 0 || stem.indexOf('\\') >= 0) {
            return null;
        }
        if (stem.length() > LsDataRaw.VMS_CLIP_ID_MAX) {
            return null;
        }
        return stem;
    }

    // ------------------------------------------------------------------ 시각·속도

    /**
     * 프레임 번호와 시각으로 프레임 재생 속도를 역산한다.
     *
     * <h3>왜 시점 목록만 쓰는가</h3>
     * <p>구간의 시작·끝 지점도 같은 축의 값이지만, 역산한 값의 쓰임이 <b>시점을 놓을 자리를 정하는
     * 것</b>이라 판정에 쓰는 표본과 적재하는 표본이 같아야 한다. 시작·끝을 섞으면 실제로 마킹되는
     * 지점과 무관한 값으로 대조하게 된다.
     *
     * @return 역산값. 시점이 둘 미만이거나 시각이 없거나 시간 간격이 0 이하면 {@code null}
     */
    private static Double deriveFps(List<Integer> frames, Map<Integer, Double> points) {
        Integer firstFrame = null;
        Integer lastFrame = null;
        for (Integer frame : frames) {
            if (points.get(frame) == null) {
                continue;
            }
            if (firstFrame == null) {
                firstFrame = frame;
            }
            lastFrame = frame;
        }
        if (firstFrame == null || lastFrame == null || firstFrame.equals(lastFrame)) {
            return null;
        }
        double seconds = points.get(lastFrame) - points.get(firstFrame);
        if (seconds <= 0) {
            return null;
        }
        double fps = (lastFrame - firstFrame) / seconds;
        if (!Double.isFinite(fps) || fps <= 0) {
            return null;
        }
        return BigDecimal.valueOf(fps).setScale(FPS_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 시각 표기를 초로 옮긴다 — {@code 시:분:초.밀리}, {@code 분:초.밀리}, {@code 초} 를 받는다.
     *
     * <p>외부가 준 표기라 자릿수를 우리가 정하지 않는다. 읽을 수 없으면 {@code null} 을 돌려 그
     * 시점의 시각만 비우고 프레임 번호는 그대로 쓴다 — 시각은 사람이 보는 값이고 <b>자리를 정하는
     * 것은 프레임 번호</b>다.
     */
    public static Double parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(":");
        if (parts.length == 0 || parts.length > 3) {
            return null;
        }
        try {
            double total = 0;
            for (String part : parts) {
                total = total * 60 + Double.parseDouble(part.trim());
            }
            return (Double.isFinite(total) && total >= 0) ? total : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 초를 {@link MarkItem} 이 받는 {@code 분:초} 표기로 옮긴다.
     *
     * <p>그 형식은 분이 네 자리까지라 더 긴 영상의 뒷부분은 담을 수 없다. 담을 수 없으면 형식을
     * 어기고 채우는 대신 <b>비운다</b> — 시각은 없어도 되지만(선택 항목) 형식이 어긋나면 그 마킹을
     * 읽는 쪽이 통째로 거부한다.
     */
    public static String timestampOf(Double seconds) {
        if (seconds == null) {
            return null;
        }
        long total = (long) Math.floor(seconds);
        long minutes = total / 60;
        long remainder = total % 60;
        if (minutes > MAX_TIMESTAMP_MINUTES) {
            return null;
        }
        return String.format("%02d:%02d", minutes, remainder);
    }

    // ------------------------------------------------------------------ 보조

    private static MarkingDocument unreadable(String message) {
        return new MarkingDocument(null, null, null, 0, List.of(), null,
                List.of(MarkingWarning.of(MarkingImportWarningCode.UNREADABLE_DOCUMENT, message)));
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isTextual() ? node.textValue() : node.asText(null);
        return (value == null || value.isBlank()) ? null : value;
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        try {
            return Integer.valueOf(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
