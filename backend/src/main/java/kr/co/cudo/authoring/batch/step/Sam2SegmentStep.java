package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SAM2 segment 단계 (Phase 5 — SAM2, Phase 2 — 토글 분기 + 인메모리 힌트 병합).
 * <p>
 * 입력 소스(라벨 단위):
 * <ol>
 *   <li>DB BBOX — YOLO 가 BBOX_ENABLED=true 인 라벨에 대해 LS_DATA_LBL 에 저장한 row.
 *       기존 BOTH 라벨 경로.</li>
 *   <li>upstreamHints — YOLO 가 POLYGON_ENABLED=true 인 라벨에 대해 인메모리로 전달.
 *       특히 BBOX_ENABLED=false, POLYGON_ENABLED=true 인 POLYGON_ONLY 라벨은 본 경로로만 들어옴.</li>
 * </ol>
 * <p>
 * 중복 제거 (Phase 4): 동일 {@code (srcSn, label, trackId)} 키로 dedup. DB BBOX 가 있으면 그 좌표를 우선 사용.
 * trackId 는 ai-server 가 부여한 객체 ID (Integer). 같은 라벨이라도 다른 trackId 면 별도 객체로
 * 간주하여 SAM2 호출을 분리한다. trackId 가 null 인 경우(legacy/저신뢰 fallback)는 라벨 단위 dedup 으로
 * 자연 흡수된다 (record equality 의 null 처리).
 * <p>양쪽에서 동일 (srcSn, label, trackId) 가 들어오면 SAM2 는 1회만 호출되고 POLYGON 도 1건만 저장된다.
 * <p>
 * 토글 방어:
 * <ul>
 *   <li>라벨별 {@code polygon=false} 면 SAM2 호출 skip + WARN 로그.
 *       (Phase 1 의 (false,false) 거부로 정상 흐름에서는 발생하지 않으나 방어 코드.)</li>
 *   <li>togglesFor empty (매핑 없음) → 모든 라벨이 {@link AnnotationToggle#BOTH} 로 처리 (기존 동작).</li>
 * </ul>
 * <p>
 * 보안:
 *  - Path Manipulation (CWE-22): baseRawPath 기준 경로 범위 내로 제한.
 */
@Slf4j
@Component
public class Sam2SegmentStep {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final VideoRepository videoRepository;
    private final PresetLabelLookupService presetLabelLookup;
    private final ObjectMapper objectMapper;
    private final Path baseRawPath;

    public Sam2SegmentStep(AiServerClient aiServerClient,
                           LsDataSrcRepository srcRepository,
                           LsDataLblRepository lblRepository,
                           VideoRepository videoRepository,
                           PresetLabelLookupService presetLabelLookup,
                           ObjectMapper objectMapper,
                           @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.aiServerClient = aiServerClient;
        this.srcRepository = srcRepository;
        this.lblRepository = lblRepository;
        this.videoRepository = videoRepository;
        this.presetLabelLookup = presetLabelLookup;
        this.objectMapper = objectMapper;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
    }

    /**
     * 단일 영상의 모든 프레임에 대해 SAM2 segment 호출 + POLYGON 라벨을 저장한다.
     *
     * @param rawSn          LS_DATA_RAW.RAW_SN
     * @param upstreamHints  YoloAutolabelStep 이 발행한 인메모리 BBOX 힌트 (POLYGON_ONLY 라벨 포함)
     * @return 저장된 POLYGON row 수
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn, List<BbHint> upstreamHints) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        List<BbHint> hints = upstreamHints == null ? List.of() : upstreamHints;

        // 이벤트 타입별 프리셋 토글 조회 (fail-safe: 미매핑이면 모든 라벨 BOTH 처리)
        String eventTypeCd = videoRepository.findById(rawSn)
                .map(LsDataRaw::getEvntTypeCd)
                .orElse(null);
        Optional<Map<String, AnnotationToggle>> togglesOpt = presetLabelLookup.togglesFor(eventTypeCd);

        // srcSn → upstream hint list (프레임 단위 빠른 조회)
        Map<Long, List<BbHint>> hintsBySrc = groupHintsBySrc(hints);

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        int saved = 0;
        for (LsDataSrc src : frames) {
            // (srcSn, label) 키로 중복 제거하면서 SAM2 호출 단위(SegmentJob)를 생성
            List<SegmentJob> jobs = buildJobs(src, hintsBySrc.getOrDefault(src.getSrcSn(), List.of()));
            if (jobs.isEmpty()) {
                continue;
            }
            String relPath = resolveImagePath(src);
            String imageB64 = readImageAsBase64(relPath);
            for (SegmentJob job : jobs) {
                AnnotationToggle toggle = resolveToggle(togglesOpt, job.label);
                if (toggle == null) {
                    // 매핑 존재 + 허용 라벨에 미포함 → 노이즈 제거 (방어)
                    continue;
                }
                if (!toggle.polygon()) {
                    // POLYGON 비활성 라벨 — 정상 흐름에서는 YOLO 가 hint 를 미발행하므로 도달하지 않음.
                    // DB BBOX 만 BBOX_ONLY 라벨로 들어왔다면 본 방어 코드가 발동.
                    log.warn("[Batch] sam2 skipped polygonDisabled label={} rawSn={} srcSn={}",
                            job.label, rawSn, src.getSrcSn());
                    continue;
                }
                Sam2Response resp = callSam2(imageB64, job.box, src.getSrcSn());
                if (resp == null || resp.polygon() == null) {
                    continue;
                }
                BigDecimal score = BigDecimal.valueOf(resp.score()).setScale(4, RoundingMode.HALF_UP);
                lblRepository.save(LsDataLbl.createAutoPolygon(
                        src.getSrcSn(), job.label, serialize(resp.polygon()), score));
                saved++;
            }
        }
        log.info("[Batch][Sam2] saved polygons rawSn={} count={}", rawSn, saved);
        return saved;
    }

    private Sam2Response callSam2(String imageB64, List<Double> box, Long srcSn) {
        try {
            return aiServerClient.segment(new Sam2Request(imageB64, null, box))
                    .block(Duration.ofSeconds(70));
        } catch (RuntimeException e) {
            log.error("[Batch][Sam2] failed srcSn={} err={}", srcSn, e.getMessage());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 호출 실패", e);
        }
    }

    /**
     * (srcSn, label, trackId) 키로 dedup 한 SAM2 호출 단위를 생성한다. (Phase 4)
     * <p>DB BBOX 우선 — 같은 (라벨, trackId) 가 hint 로도 들어오면 hint 는 무시.
     * trackId 가 null 인 경우 record equality 의 null 비교로 자연스럽게 label 단위 dedup 이 된다.
     */
    private List<SegmentJob> buildJobs(LsDataSrc src, List<BbHint> frameHints) {
        // DB BBOX 우선 등록 (insertion-order 보존: BBOX → hint)
        LinkedHashMap<DedupKey, SegmentJob> jobs = new LinkedHashMap<>();
        List<LsDataLbl> bboxes = lblRepository.findBySrcSnAndAutoLblYn(src.getSrcSn(), LsDataLbl.AUTO_YES).stream()
                .filter(l -> LsDataLbl.TYPE_BBOX.equals(l.getLblTypeCd()))
                .toList();
        for (LsDataLbl lbl : bboxes) {
            Integer trackId = parseTrackId(lbl.getTrackId());
            DedupKey key = new DedupKey(src.getSrcSn(), lbl.getLabel(), trackId);
            jobs.putIfAbsent(key, new SegmentJob(lbl.getLabel(), parseBbox(lbl.getPointsJson())));
        }
        for (BbHint h : frameHints) {
            DedupKey key = new DedupKey(h.srcSn(), h.label(), h.trackId());
            jobs.putIfAbsent(key, new SegmentJob(h.label(), h.points()));
        }
        return new ArrayList<>(jobs.values());
    }

    /**
     * LsDataLbl.trackId 는 String(VARCHAR) 으로 저장되지만 ai-server 는 Integer 를 부여한다.
     * dedup 키는 BbHint(Integer) 와 일치해야 하므로 정수 파싱. 파싱 실패는 null 로 fallback.
     */
    private static Integer parseTrackId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            // legacy/임의 문자열 trackId — null 로 fallback (라벨 단위 dedup 으로 흡수)
            return null;
        }
    }

    private static Map<Long, List<BbHint>> groupHintsBySrc(List<BbHint> hints) {
        if (hints.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<BbHint>> bySrc = new LinkedHashMap<>();
        for (BbHint h : hints) {
            bySrc.computeIfAbsent(h.srcSn(), k -> new ArrayList<>()).add(h);
        }
        return bySrc;
    }

    private static AnnotationToggle resolveToggle(Optional<Map<String, AnnotationToggle>> togglesOpt,
                                                  String rawLabel) {
        if (togglesOpt.isEmpty()) {
            return AnnotationToggle.BOTH;
        }
        if (rawLabel == null) {
            return null;
        }
        String normalized = rawLabel.trim().toLowerCase();
        return togglesOpt.get().get(normalized);
    }

    private String resolveImagePath(LsDataSrc src) {
        if (src.getDeidFilePath() != null) {
            Path deidPath = baseRawPath.resolve(src.getDeidFilePath()).normalize();
            if (deidPath.startsWith(baseRawPath) && Files.exists(deidPath)) {
                return src.getDeidFilePath();
            }
        }
        return src.getFilePath();
    }

    private String readImageAsBase64(String relativePath) {
        Path imagePath = baseRawPath.resolve(relativePath).normalize();
        if (!imagePath.startsWith(baseRawPath)) {
            // HIGH-2 fix (CWE-209): 클라이언트 응답에 내부 스토리지 경로 노출 금지.
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 경로 범위 초과");
        }
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            // HIGH-2 fix (CWE-209): 내부 경로 노출 금지.
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 파일 읽기 실패", e);
        }
    }

    private List<Double> parseBbox(String pointsJson) {
        if (pointsJson == null) {
            return null;
        }
        try {
            // MEDIUM-3 fix: raw type 대신 TypeReference 사용 → 역직렬화 타입 안전성 확보.
            return objectMapper.readValue(pointsJson, new TypeReference<List<Double>>() {});
        } catch (JsonProcessingException e) {
            log.warn("[Batch][Sam2] bbox 파싱 실패 — box 없이 호출: {}", e.getMessage());
            return null;
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "polygon 직렬화 실패", e);
        }
    }

    /**
     * SAM2 호출 단위 — 라벨 + bbox 좌표.
     * 동일 (srcSn, label, trackId) 의 DB BBOX 와 upstreamHint 가 동시 존재할 경우 DB BBOX 좌표가 우선.
     */
    private record SegmentJob(String label, List<Double> box) {
    }

    /**
     * SAM2 dedup 키 (Phase 4) — 같은 라벨이라도 trackId 가 다르면 별도 객체로 간주.
     * trackId=null 인 경우 record equality 의 null 처리로 라벨 단위 dedup 으로 fallback.
     */
    private record DedupKey(long srcSn, String label, Integer trackId) {
    }
}
