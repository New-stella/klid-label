package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionChangeRequest;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 해상도 변경(RESOLUTION) 서비스 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p><b>정책(R1)</b>: 검수 완료(APPROVED) 원본 영상의 프레임 이미지셋(LS_DATA_SRC.SRC_FILE_PATH_NM)을
 * 표준 하위 해상도(RES_1080P/RES_720P/RES_480P)로 종횡비 보존 <b>다운스케일</b>한다. 영상(비디오)
 * 재생성·라벨 좌표 스케일 복사·새 PENDING 영상(LS_DATA_RAW) 생성은 하지 않으며, 산출물은
 * 다운스케일 이미지셋 + {@code LS_RESOLUTION_EXPORT} 1행뿐이다.
 *
 * <p><b>트랜잭션 경계 (HIGH-④)</b>: 이미지 다운스케일(파일 I/O)은 DB 트랜잭션 밖에서 수행하고,
 * 모든 프레임 생성 완료 후에만 {@link VideoResolutionPersister#persist} 의 {@code REQUIRES_NEW}
 * 단일 트랜잭션으로 EXPORT 1행을 INSERT 한다(파일→DB 순서).
 *
 * <p><b>RBAC 정책</b>: REVIEWER 역할 기반 접근. 영상별 소유권 개념은 없으며 모든 APPROVED 영상에
 * 허용한다(의도된 정책 — IDOR 아님). 권한 검증은 Controller {@code @PreAuthorize("hasRole('REVIEWER')")}
 * 가 1차 책임이고, 본 서비스는 비즈니스 규칙만 다룬다.
 *
 * <p><b>보안</b>: src/dst 경로는 모두 normalize + storageRawPath base 검증(CWE-22), 로그/예외는
 * 경로 hash 마스킹·추상 메시지(CWE-209)로 처리한다. 동시 실행은 {@link Semaphore}(fair) 상한으로
 * 자원 고갈(DoS, API4:2023)을 방지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoResolutionService {

    /** 프레임 청크 순회 크기 (대용량 다운스케일 — MEDIUM). */
    private static final int FRAME_CHUNK_SIZE = 500;

    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository statusRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsResolutionExportRepository exportRepository;
    private final ImageResizer imageResizer;
    private final VideoResolutionPersister persister;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /** 이미지 다운스케일 동시 실행 상한 (DoS 방지, API4:2023). */
    @Value("${authoring.resolution.resize-max-concurrent:2}")
    private int resizeMaxConcurrent;

    /** Semaphore 획득 타임아웃(초) — 무한 대기로 인한 톰캣 스레드 점유 방지. */
    @Value("${authoring.resolution.resize-acquire-timeout-sec:5}")
    private long resizeAcquireTimeoutSec;

    /** 동시 실행 제한용 공정(fair) Semaphore — 단일 인스턴스 공유. {@link #resizeSemaphore()} 로 지연 초기화. */
    private volatile Semaphore resizeSemaphore;

    /**
     * 해상도 변경 실행 — 검증 → 프레임 이미지 다운스케일(트랜잭션 밖) → EXPORT INSERT(REQUIRES_NEW).
     *
     * @param rawSn   원본 영상 PK (검수 완료 + 비-증강본만 허용)
     * @param request 표준 하위 해상도 프리셋
     * @param regId   등록자(REVIEWER) 식별자 (감사 추적용)
     * @return EXPORT_SN + 원본/타겟 해상도 + 프레임 개수
     */
    public ResolutionChangeResponse changeResolution(Long rawSn, ResolutionChangeRequest request, String regId) {
        ResolutionPreset preset = request.preset();

        // 1) 조회 + 검증 (APPROVED + 비-증강본)
        LsDataRaw parent = loadAndValidate(rawSn);

        // 2) 동일 영상+해상도 중복 사전 체크 (HIGH-② 1차선)
        if (exportRepository.existsByDataRawSnAndTargetResCd(rawSn, preset.name())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일 영상에 해당 해상도 변경 결과가 이미 존재합니다.");
        }

        // 3) 프레임 조회 — 0건이면 조기 거부 (HIGH-⑥), EXPORT 행 미생성
        long frameTotal = srcRepository.countByRawSn(rawSn);
        if (frameTotal == 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "다운스케일할 프레임이 없습니다.");
        }

        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();

        // 4) 첫 프레임 실측으로 원본 해상도 산정 (HIGH-③ 업스케일 거부 — enum 비교 금지)
        LsDataSrc firstFrame = srcRepository.findByRawSnAndFrameNo(rawSn, 0)
                .orElseGet(() -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn).stream()
                        .min(Comparator.comparing(LsDataSrc::getFrameNo))
                        .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT, "다운스케일할 프레임이 없습니다.")));
        Path firstSrcPath = resolveSafeSrc(base, firstFrame.getSrcFilePathNm());
        int[] dim = imageResizer.readDimensions(firstSrcPath);
        int srcW = dim[0];
        int srcH = dim[1];
        if (srcW <= 0 || srcH <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
        }

        // 5) 타겟 해상도 산정 — 종횡비 보존(height 기준), targetW 짝수 보정 (MEDIUM)
        int targetH = preset.height();
        int targetW = evenScaled(srcW, srcH, targetH);

        // 6) 업스케일 거부 — 실측 원본 높이와 비교 (HIGH-③)
        if (targetH >= srcH) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "목표 해상도가 원본보다 작아야 합니다(업스케일 불가).");
        }
        if (targetW < 2 || targetH < 2) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 해상도가 너무 작아 해당 프리셋으로 다운스케일 불가");
        }

        // 7) 출력 디렉토리 — 결정론적 경로, 재시도 시 멱등 초기화 (HIGH-①)
        Path outputDir = resolveSafeOutputDir(base, rawSn, preset.name());

        // 8) 다운스케일 실행 — 동시 실행 상한 Semaphore 보호 (DoS)
        acquireResizeSlot();
        try {
            resetOutputDir(outputDir);
            int frameCount = downscaleAllFrames(rawSn, base, outputDir, targetW, targetH, (int) frameTotal);

            // 9) 파일→DB 순서 — 모든 프레임 완료 후 EXPORT 1행 INSERT (HIGH-④)
            try {
                return persister.persist(parent, preset, outputDir.toString(),
                        srcW, srcH, targetW, targetH, frameCount, regId);
            } catch (DataIntegrityViolationException e) {
                // UK(DATA_RAW_SN, GOAL_RES_CD) 최종 방어 — 동시 요청 경합 (HIGH-②)
                deleteDirQuietly(outputDir);
                throw new CustomException(ErrorCode.CONFLICT,
                        "동일 영상에 해당 해상도 변경 결과가 이미 존재합니다.");
            }
        } catch (RuntimeException e) {
            deleteDirQuietly(outputDir);
            throw e;
        } finally {
            resizeSemaphore().release();
        }
    }

    /** 프레임 청크 순회 다운스케일. K번째 실패 시 RuntimeException 전파(상위가 디렉토리 정리). */
    private int downscaleAllFrames(Long rawSn, Path base, Path outputDir, int targetW, int targetH, int frameTotal) {
        int processed = 0;
        int page = 0;
        int totalPages = Math.max(1, (frameTotal + FRAME_CHUNK_SIZE - 1) / FRAME_CHUNK_SIZE);
        while (page < totalPages) {
            Pageable pageable = PageRequest.of(page, FRAME_CHUNK_SIZE);
            List<LsDataSrc> chunk = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn, pageable).getContent();
            if (chunk.isEmpty()) {
                break;
            }
            for (LsDataSrc frame : chunk) {
                Path src = resolveSafeSrc(base, frame.getSrcFilePathNm());
                if (!Files.exists(src)) {
                    throw new CustomException(ErrorCode.NOT_FOUND, "원본 프레임 파일을 찾을 수 없습니다.");
                }
                Path dst = outputDir.resolve(outputFileName(frame, src));
                imageResizer.resize(src, dst, targetW, targetH);
                processed++;
            }
            page++;
        }
        if (processed == 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "다운스케일할 프레임이 없습니다.");
        }
        return processed;
    }

    private String outputFileName(LsDataSrc frame, Path src) {
        String name = src.getFileName() != null ? src.getFileName().toString() : (frame.getFrameNo() + ".jpg");
        return name;
    }

    /**
     * 이미지 다운스케일 슬롯 획득 (DoS). 트랜잭션 밖에서 호출하며, 타임아웃 내 획득 실패 시
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

        // APPROVED 검증 — LS_RAW_DATA_STATUS 기준
        boolean approved = statusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .anyMatch(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()));
        if (!approved) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "검수 완료(APPROVED)된 영상만 해상도 변경할 수 있습니다.");
        }
        return parent;
    }

    /** 종횡비 보존: targetH 기준으로 targetW 산정 후 짝수로 내림(인코더 호환·일관성). */
    private int evenScaled(int srcW, int srcH, int targetH) {
        if (srcH <= 0) {
            return 0;
        }
        int scaledW = (int) Math.round((double) srcW * targetH / srcH);
        if (scaledW < 0) {
            scaledW = 0;
        }
        return scaledW - (scaledW % 2);
    }

    /** src(원본 프레임) 경로 normalize + storageRawPath base 검증 (CWE-22). */
    private Path resolveSafeSrc(Path base, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "원본 프레임 경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : base.resolve(candidate).normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /**
     * 출력 디렉토리를 storageRawPath base 하위 결정론적 경로로 해석 + normalize 검증 (CWE-22, HIGH-①).
     * {@code <storageRawPath>/resolution/<rawSn>/<GOAL_RES_CD>/}.
     */
    private Path resolveSafeOutputDir(Path base, Long rawSn, String preset) {
        Path resolved = base.resolve("resolution")
                .resolve(String.valueOf(rawSn))
                .resolve(preset)
                .normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "다운스케일 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /** 출력 디렉토리 멱등 초기화 — 재시도 시 기존 잔여물 제거 후 재생성 (HIGH-①). */
    private void resetOutputDir(Path outputDir) {
        deleteDirQuietly(outputDir);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            log.error("[Video][Resolution] output dir create failed dir={}", maskPath(outputDir));
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "출력 디렉토리를 준비할 수 없습니다.");
        }
    }

    /** 부분 실패/예외 시 출력 디렉토리 전체 정리 (HIGH-①④⑤). */
    private void deleteDirQuietly(Path outputDir) {
        if (outputDir == null || !Files.exists(outputDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(outputDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
            log.warn("[Video][Resolution] output dir cleaned dir={}", maskPath(outputDir));
        } catch (IOException e) {
            log.error("[Video][Resolution] output dir cleanup failed dir={}", maskPath(outputDir));
        }
    }

    private String maskPath(Path p) {
        return p == null ? "null" : Integer.toHexString(p.toString().hashCode());
    }
}
