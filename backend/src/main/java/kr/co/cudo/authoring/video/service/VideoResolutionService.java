package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionChangeRequest;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoResizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 해상도 변경(RESOLUTION) 서비스 — Phase 3 (RQ-SFR-07-02).
 *
 * <p>검수 완료(APPROVED) 영상을 REVIEWER 가 배율 프리셋으로 리사이즈하면, 라벨 좌표를
 * 동일 배율로 스케일 복사한 새 PENDING 영상(RAW_SN)을 만든다. 새 영상은 기존 배정/검수
 * 흐름을 그대로 탄다.
 *
 * <p><b>트랜잭션 경계 (HIGH-①)</b>: ffprobe/ffmpeg 같은 장시간 외부 프로세스는 DB 트랜잭션
 * 밖에서 실행한다(HikariCP 커넥션 고갈 방지). 본 오케스트레이션 메서드는 {@code @Transactional}
 * 이 아니며, DB INSERT 전체만 {@link VideoResolutionPersister#persist} 의 {@code REQUIRES_NEW}
 * 단일 트랜잭션으로 묶는다({@code FfmpegFrameExtractor} 와 동일 사유).
 *
 * <p><b>RBAC 정책</b>: REVIEWER 역할 기반 접근. 영상별 소유권 개념은 없으며 모든 APPROVED 영상에
 * 허용한다(AugmentRequestService 와 동일한 의도된 정책 — IDOR 아님). 권한 검증은 Controller
 * {@code @PreAuthorize("hasRole('REVIEWER')")} 가 1차 책임이고, 본 서비스는 비즈니스 규칙만 다룬다.
 *
 * <p><b>보안</b>: ffmpeg/ffprobe 인자는 ProcessBuilder 리스트로 분리(CWE-78), src/dst 경로는
 * 모두 normalize + storageRawPath base 검증(CWE-22, {@link VideoStreamService#resolveSafe} 패턴
 * 재사용), 로그/예외는 경로 hash 마스킹·추상 메시지(CWE-209)로 처리한다. 또한 ffmpeg 동시 실행은
 * {@link Semaphore}(fair) 로 상한을 두어 자원 고갈(DoS, API4:2023)을 방지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoResolutionService {

    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository statusRepository;
    private final VideoProbe videoProbe;
    private final VideoResizer videoResizer;
    private final VideoResolutionPersister persister;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /** ffmpeg resize 동시 실행 상한 (DoS 방지, API4:2023). */
    @Value("${authoring.ffmpeg.resize-max-concurrent:2}")
    private int resizeMaxConcurrent;

    /** Semaphore 획득 타임아웃(초) — 무한 대기로 인한 톰캣 스레드 점유 방지. */
    @Value("${authoring.ffmpeg.resize-acquire-timeout-sec:5}")
    private long resizeAcquireTimeoutSec;

    /** ffmpeg 동시 실행 제한용 공정(fair) Semaphore — 단일 인스턴스 공유. {@link #resizeSemaphore()} 로 지연 초기화. */
    private volatile Semaphore resizeSemaphore;

    /**
     * 해상도 변경 실행 — 검증 → ffprobe/ffmpeg(트랜잭션 밖) → DB INSERT(REQUIRES_NEW).
     *
     * @param rawSn   원본 영상 PK (검수 완료 + 비-증강본만 허용)
     * @param request 배율 프리셋
     * @return 새 영상 RAW_SN + 해상도/배율/복사 건수
     */
    public ResolutionChangeResponse changeResolution(Long rawSn, ResolutionChangeRequest request) {
        ResolutionPreset preset = request.preset();
        double factor = preset.factor();

        // 1) 조회 + 검증 (read-only 트랜잭션 — 외부 프로세스 전에 종료)
        LsDataRaw parent = loadAndValidate(rawSn);

        // 2) src(원본) 경로 검증 (CWE-22, MEDIUM-1) — VideoStreamService 와 동일 패턴.
        //    DB 값(getRawFilePathNm)이 storageRawPath base 밖이면 ffprobe/ffmpeg 실행 전 거부.
        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path source = VideoStreamService.resolveSafe(base, parent.getRawFilePathNm());

        // 3) (트랜잭션 밖) ffprobe → 원본 해상도 검증 (HIGH-⑥)
        VideoProbe.Dimensions dim = videoProbe.probe(source);
        validateDimensions(dim);
        int srcW = dim.width();
        int srcH = dim.height();

        // 3) target 계산 — 짝수 강제(yuv420p), <2 거부 (HIGH-⑧)
        int targetW = evenScaled(srcW, factor);
        int targetH = evenScaled(srcH, factor);
        if (targetW < 2 || targetH < 2) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "원본 해상도가 너무 작아 해당 프리셋으로 리사이즈 불가");
        }

        // 4) 중복 자식 사전 체크 (HIGH-②⑩) — UK(VMS_CLIP_ID) 자연 방어의 1차선
        String newClipId = parent.getVmsClipId() + "_RES_" + preset.name();
        videoRepository.findByVmsClipId(newClipId).ifPresent(existing -> {
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일 영상에 해당 해상도 변경 결과가 이미 존재합니다.");
        });

        // 5) dst 경로 검증 (CWE-22) — 결정론적 경로(재시도 시 덮어쓰기)
        Path dst = resolveSafeDst(rawSn, preset.name(), source);

        // 6) (트랜잭션 밖) ffmpeg resize — 동시 실행 상한 Semaphore 보호 (DoS, MEDIUM-2).
        acquireResizeSlot();
        try {
            videoResizer.resize(source, dst, factor, targetW, targetH);

            // 7) DB INSERT 전체 — REQUIRES_NEW (HIGH-④). 실패 시 고아 출력 파일 정리 (HIGH-③).
            return persister.persist(parent, preset, dst.toString(), factor, srcW, srcH, targetW, targetH);
        } catch (RuntimeException e) {
            deleteOrphan(dst);
            throw e;
        } finally {
            resizeSemaphore().release();
        }
    }

    /**
     * ffmpeg 동시 실행 슬롯 획득 (MEDIUM-2). 트랜잭션 밖에서 호출하며, 타임아웃 내 획득 실패 시
     * {@link ErrorCode#TOO_MANY_REQUESTS}(429) 로 거부하여 무한 대기·자원 고갈을 방지한다.
     */
    private void acquireResizeSlot() {
        try {
            boolean acquired = resizeSemaphore().tryAcquire(resizeAcquireTimeoutSec, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[Video][Resolution] resize slot busy, rejected (max={})", resizeMaxConcurrent);
                throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                        "동시 처리 가능한 해상도 변경 요청 수를 초과했습니다. 잠시 후 다시 시도해 주세요.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "해상도 변경 요청 처리가 중단되었습니다.");
        }
    }

    /** 공정(fair) Semaphore 지연 초기화 — @Value 주입 이후 최초 사용 시 1회 생성, 이후 단일 인스턴스 공유. */
    private Semaphore resizeSemaphore() {
        Semaphore s = resizeSemaphore;
        if (s == null) {
            synchronized (this) {
                s = resizeSemaphore;
                if (s == null) {
                    s = new Semaphore(Math.max(resizeMaxConcurrent, 0), true);
                    resizeSemaphore = s;
                }
            }
        }
        return s;
    }

    /** 조회 + 비-증강본 + APPROVED 검증. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public LsDataRaw loadAndValidate(Long rawSn) {
        LsDataRaw parent = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다: rawSn=" + rawSn));

        // 중첩 증강 거부 — 이미 증강/리사이즈 산출물이면 원본 아님 (정책)
        if (parent.getParentRawSn() != null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 영상에만 해상도 변경 가능");
        }

        // APPROVED 검증 — LS_RAW_DATA_STATUS 기준 (AugmentRequestService 와 동일 모델)
        boolean approved = statusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .anyMatch(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()));
        if (!approved) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다.");
        }
        return parent;
    }

    /** ffprobe 결과 검증 (HIGH-⑥) — video stream 없음/0 해상도. 경로/원인은 log 만, 응답은 추상 메시지. */
    private void validateDimensions(VideoProbe.Dimensions dim) {
        if (dim == null || dim.width() <= 0 || dim.height() <= 0) {
            log.error("[Video][Resolution] invalid source dimensions {}", dim);
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 영상의 해상도를 확인할 수 없습니다.");
        }
    }

    /** factor 적용 후 짝수로 내림 (yuv420p 인코딩 요구). 음수 방지 위해 0 하한. */
    private int evenScaled(int dimension, double factor) {
        int scaled = (int) Math.floor(dimension * factor);
        if (scaled < 0) {
            scaled = 0;
        }
        return scaled - (scaled % 2);
    }

    /**
     * dst 경로를 storageRawPath base 하위 결정론적 경로로 해석 + normalize 검증 (CWE-22).
     * {@code <storageRawPath>/resolution/<rawSn>/<preset>/<원본파일명>}.
     */
    private Path resolveSafeDst(Long rawSn, String preset, Path source) {
        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
        String fileName = source.getFileName() != null ? source.getFileName().toString() : (rawSn + ".mp4");
        Path resolved = base.resolve("resolution")
                .resolve(String.valueOf(rawSn))
                .resolve(preset)
                .resolve(fileName)
                .normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "리사이즈 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /** DB INSERT 실패 시 ffmpeg 출력 고아 파일 정리 (HIGH-③). */
    private void deleteOrphan(Path dst) {
        try {
            Files.deleteIfExists(dst);
            log.warn("[Video][Resolution] orphan output cleaned path={}", maskPath(dst));
        } catch (Exception cleanupEx) {
            log.error("[Video][Resolution] orphan cleanup failed path={} err={}",
                    maskPath(dst), cleanupEx.getMessage());
        }
    }

    private String maskPath(Path p) {
        return p == null ? "null" : Integer.toHexString(p.toString().hashCode());
    }
}
