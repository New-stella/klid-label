package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

/**
 * 영상 기술메타 추출 비동기 실행기 — NIA export Phase 2.
 *
 * <p>{@code VideoMetaExtractBridge} 가 {@code VideoIngestedEvent} 수신(AFTER_COMMIT) 후 호출한다.
 * 적재 트랜잭션이 커밋된 뒤 별도 스레드에서 {@code video.*} 메타를 저장한다 — 선두 비식별
 * ({@code AsyncDeidentifyRunner})과 독립적으로 병행한다.
 *
 * <h3>소스 우선순위 — 관제 인입값 우선(설계 §6-2)</h3>
 * <p>관제가 {@code LS_DATA_INGEST} 에 이미 넣어 준 기술메타를 먼저 읽고, <b>인입이 채우지 못한 키가
 * 있을 때만</b> 영상 파일을 ffprobe 로 조사한다. 인입이 전 키를 채우면 NAS 접근·ffprobe 실행이 통째로
 * 생략된다. 인입 행이 없는 <b>파생영상(증강·해상도)</b>은 종전대로 전량 probe 다.
 *
 * <p>probe 가 실패해도 인입값은 적재한다(부분 결손 &gt; 전량 결손).
 *
 * <p><b>측정 대상은 그 영상 자신의 파일</b>이다({@link #resolveProbeSource}): 원본 영상은
 * {@code RAW_FILE_PATH_NM}, <b>파생영상(증강·해상도)은 자신의 비식별 사본</b>. 파생 경로에서는
 * {@code AsyncAugmentFrameRunner} 가 사본 확정(Phase C) 이후에 호출하므로 사본이 이미 존재한다.
 *
 * <h3>graceful 격리 (S1)</h3>
 * ffprobe 미설치/파일 접근불가/probe 예외/저장 실패는 모두 WARN 로깅 후 삼킨다 — 적재·비식별
 * 파이프라인을 절대 중단·롤백시키지 않는다. blocking I/O(ffprobe)는 트랜잭션 밖에서 수행하고,
 * DB 쓰기만 {@link VideoMetaService} 의 REQUIRES_NEW 독립 트랜잭션으로 위임한다.
 *
 * <p>원본 경로는 로그에 평문 노출하지 않고 해시로 마스킹한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncVideoMetaRunner {

    private final VideoRepository videoRepository;
    private final VideoProbe videoProbe;
    private final VideoMetaService videoMetaService;
    /**
     * 파생영상(증강·해상도)의 <b>비식별 사본</b> 경로 진실원. 파일명은 고정이 아니므로 조합·추측하지
     * 않고 {@code DE_IDNTF_FILE_PATH_NM} 에 적재된 값을 읽는다(프로젝트 규약 "문자열 치환 도출 아님").
     */
    private final LsDeidentProcLogRepository deidentProcLogRepository;

    @Async("batchAsyncExecutor")
    public void runAsync(Long rawSn) {
        try {
            // ① 관제 인입값 우선 — 파일을 열기 전에 이미 받은 값이 있는지 본다.
            Map<String, String> ingestValues = videoMetaService.loadIngestMeta(rawSn);
            // ② 인입이 전 키를 채웠으면 NAS 접근·ffprobe 자체를 건너뛴다(키 단위 폴백 판정은 서비스 소유).
            VideoMeta meta = VideoMetaService.needsProbe(ingestValues) ? probe(rawSn) : null;
            if (meta == null && (ingestValues == null || ingestValues.isEmpty())) {
                // 적재할 값이 한 건도 없다 — 사유는 probe 단계에서 이미 남겼다.
                return;
            }
            videoMetaService.upsertVideoMeta(rawSn, ingestValues, meta);
        } catch (RuntimeException e) {
            // graceful — probe/저장 실패가 적재·비식별 파이프라인을 중단시키지 않는다(@Async, 예외 삼킴).
            log.warn("[VideoMeta] probe/store failed rawSn={} cause={} — 적재/파이프라인 지속",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 인입이 채우지 못한 키를 메우기 위한 ffprobe 실행 — 실패·미상은 예외 없이 {@code null} 로 돌려
     * 인입값만이라도 적재되게 한다(부분 결손 &gt; 전량 결손).
     */
    private VideoMeta probe(Long rawSn) {
        String filePath = resolveProbeSource(rawSn).orElse(null);
        if (!StringUtils.hasText(filePath)) {
            log.warn("[VideoMeta] probe source not found rawSn={} — skip probe", rawSn);
            return null;
        }
        Path videoPath;
        try {
            videoPath = Paths.get(filePath);
        } catch (RuntimeException e) {
            log.warn("[VideoMeta] invalid path rawSn={} cause={} — skip probe",
                    rawSn, e.getClass().getSimpleName());
            return null;
        }
        VideoMeta meta = videoProbe.probe(videoPath);
        if (meta == null) {
            log.warn("[VideoMeta] probe returned null rawSn={} path={} — skip store",
                    rawSn, maskPath(videoPath));
            return null;
        }
        log.info("[VideoMeta] extracted rawSn={} path={}", rawSn, maskPath(videoPath));
        return meta;
    }

    /**
     * probe 대상 파일 경로 조회 — REQUIRES_NEW readOnly (AsyncDeidentifyRunner.loadRaw 패턴).
     *
     * <ul>
     *   <li><b>원본 영상</b>({@code ORGNL_RAW_SN} null) — {@code RAW_FILE_PATH_NM}(관제 NAS 원본). 종전 동작.</li>
     *   <li><b>파생영상</b>({@code ORGNL_RAW_SN} non-null, 증강·해상도) — <b>파생 자신의 비식별 사본</b>.
     *       파생영상에는 원본이 존재하지 않으며, 기술메타(RESL/FPS/BIT_RT/FILE_SZ/VDO_CDC)는 실제
     *       산출 파일을 측정한 값이어야 한다. 경로는 최신 SUCCEEDED
     *       {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM} <b>값</b>을 읽는다(파일명 조합 금지).
     *       값이 없으면 원본으로 폴백하지 않고 skip 한다 — 폴백하면 부모 원본(PII)을 열어 측정하게 된다.</li>
     * </ul>
     * 사본이 아직 없으면(파일 부재) probe 하지 않고 사유를 남긴다 — probe 실패를 성공으로 위장하거나
     * 엉뚱한 파일을 측정하지 않기 위함이다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    protected Optional<String> resolveProbeSource(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.getOrgnlRawSn() == null) {
            return Optional.ofNullable(raw.getRawFilePathNm());
        }
        String deidPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(StringUtils::hasText)
                .orElse(null);
        if (deidPath == null) {
            log.warn("[VideoMeta] derivative deidentified copy not recorded yet rawSn={} — skip probe", rawSn);
            return Optional.empty();
        }
        if (!Files.isRegularFile(Paths.get(deidPath))) {
            log.warn("[VideoMeta] derivative deidentified copy not present yet rawSn={} — skip probe", rawSn);
            return Optional.empty();
        }
        return Optional.of(deidPath);
    }

    /** 경로 평문 로그 금지 — 해시로 마스킹(VideoResolutionService.maskPath 패턴). */
    private String maskPath(Path p) {
        return p == null ? "null" : Integer.toHexString(p.toString().hashCode());
    }
}
