package kr.co.cudo.authoring.batch.runner;

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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 영상 기술메타(ffprobe) 추출 비동기 실행기 — NIA export Phase 2.
 *
 * <p>{@code VideoMetaExtractBridge} 가 {@code VideoIngestedEvent} 수신(AFTER_COMMIT) 후 호출한다.
 * 적재 트랜잭션이 커밋된 뒤 별도 스레드에서 원본 영상 경로를 ffprobe 로 조사하고 결과를
 * {@code video.*} 메타로 저장한다 — 선두 비식별({@code AsyncDeidentifyRunner})과 독립적으로 병행한다.
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

    @Async("batchAsyncExecutor")
    public void runAsync(Long rawSn) {
        try {
            String filePath = loadRawFilePath(rawSn).orElse(null);
            if (!StringUtils.hasText(filePath)) {
                log.warn("[VideoMeta] raw/path not found rawSn={} — skip probe", rawSn);
                return;
            }
            Path videoPath;
            try {
                videoPath = Paths.get(filePath);
            } catch (RuntimeException e) {
                log.warn("[VideoMeta] invalid path rawSn={} cause={} — skip probe",
                        rawSn, e.getClass().getSimpleName());
                return;
            }
            VideoMeta meta = videoProbe.probe(videoPath);
            if (meta == null) {
                log.warn("[VideoMeta] probe returned null rawSn={} path={} — skip store",
                        rawSn, maskPath(videoPath));
                return;
            }
            videoMetaService.upsertVideoMeta(rawSn, meta);
            log.info("[VideoMeta] extracted rawSn={} path={}", rawSn, maskPath(videoPath));
        } catch (RuntimeException e) {
            // graceful — probe/저장 실패가 적재·비식별 파이프라인을 중단시키지 않는다(@Async, 예외 삼킴).
            log.warn("[VideoMeta] probe/store failed rawSn={} cause={} — 적재/파이프라인 지속",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /** 원본 영상 경로 조회 — REQUIRES_NEW readOnly (AsyncDeidentifyRunner.loadRaw 패턴). */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    protected Optional<String> loadRawFilePath(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }
        return videoRepository.findById(rawSn).map(LsDataRaw::getRawFilePathNm);
    }

    /** 경로 평문 로그 금지 — 해시로 마스킹(VideoResolutionService.maskPath 패턴). */
    private String maskPath(Path p) {
        return p == null ? "null" : Integer.toHexString(p.toString().hashCode());
    }
}
