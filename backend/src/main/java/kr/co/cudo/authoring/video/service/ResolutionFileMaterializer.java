package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import kr.co.cudo.authoring.video.service.port.VideoFileCopier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 해상도 파생 확정 — <b>Phase B(파일 I/O)</b>. 락-I/O 분리 리팩터의 2단계 (RQ-SFR-06-03 파생영상).
 *
 * <p><b>{@code @Transactional} 절대 금지 · 리포지토리 주입 금지</b> — 순수 파일 서비스다(#6). Phase A 가
 * 확정한 {@link ResolutionSnapshot}(치수·경로 전부 포함)만 받아 비디오 복사 + 프레임 리스케일을 파일로만
 * 산출한다. DB 접근 0, 부모 잠금 0 이므로 대용량 I/O 구간에서 커넥션·잠금을 점유하지 않는다.
 *
 * <p>{@link ResizeConcurrencyGate} 세마포어를 <b>비디오 복사 구간까지 확장</b>해 Phase B 전체(복사+리사이즈)를
 * 하나의 슬롯으로 통제한다 — 자원 고갈(DoS, API4:2023) 방지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionFileMaterializer {

    private final ImageResizer imageResizer;
    private final VideoFileCopier videoFileCopier;
    private final ResizeConcurrencyGate resizeGate;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 스냅샷을 파일로 산출한다 — ①비식별 비디오 복사 ②전 프레임 목표해상도 리스케일.
     * DB 접근·부모 잠금 없이 순수 파일 작업만 수행한다.
     *
     * @throws CustomException 원본 비식별 파일 부재(NOT_FOUND)·리사이즈 슬롯 초과(429) 등
     */
    public void materialize(ResolutionSnapshot snapshot) {
        resizeGate.acquire();
        try {
            // 1) 원본 비식별 비디오 복사 → 파생 RAW 경로 (경로는 Phase A 에서 CWE-22 검증 완료).
            if (!videoFileCopier.exists(snapshot.deidVideoSrc())) {
                throw new CustomException(ErrorCode.NOT_FOUND, "원본 비식별 영상 파일을 찾을 수 없습니다.");
            }
            videoFileCopier.copy(snapshot.deidVideoSrc(), snapshot.videoDst());

            // 2) 프레임 목표해상도 리스케일(축소/확대). 비식별 원본 프레임만 소스로 사용(PII 안전).
            for (ResolutionSnapshot.FrameSpec f : snapshot.frames()) {
                imageResizer.resize(f.deidSrc(), f.dst(), snapshot.targetW(), snapshot.targetH());
            }
            log.info("[Video][ResolutionDerivative][B] materialized rawSn={} frames={} target={}x{}",
                    snapshot.newRawSn(), snapshot.frames().size(), snapshot.targetW(), snapshot.targetH());
        } finally {
            resizeGate.release();
        }
    }

    /**
     * MEDIUM (#3) — 실패 시 Phase B 산출 아티팩트(리스케일 프레임 디렉토리 + 파생 비디오 파일)를
     * <b>동기 삭제 후 {@link Files#exists} 재확인</b>한다. 잔존이 확인되면 {@code false} 를 반환하여
     * 러너가 ERROR 로그 + 메트릭({@code resolution.cleanup.failed}) 을 남기게 한다.
     *
     * <p>파생 RAW 경로만 삭제 대상이다 — 원본은 절대 삭제하지 않는다. 경로는 마스킹 로그만 남긴다(CWE-209/PII).
     *
     * @param newRawSn  파생 RAW_SN (프레임 디렉토리 {@code resolution/{newRawSn}/} 산정 기준)
     * @param videoDst  파생 비디오 목적 경로(검증 완료, nullable)
     * @return 잔존 아티팩트 없음(정리 성공)=true, 삭제 후에도 잔존=false
     */
    public boolean cleanup(Long newRawSn, Path videoDst) {
        if (newRawSn == null) {
            return true;
        }
        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
        boolean clean = true;

        // 1) 리스케일 프레임 디렉토리 resolution/{newRawSn}/ 재귀 삭제 + 잔존 재확인.
        try {
            Path framesDir = resolveSafeDir(base, "resolution/" + newRawSn);
            deleteRecursivelyQuietly(framesDir);
            if (Files.exists(framesDir)) {
                clean = false;
            }
        } catch (RuntimeException e) {
            clean = false;
            log.warn("[Video][ResolutionDerivative][B] frames dir cleanup skipped newRawSn={} cause={}",
                    newRawSn, e.getClass().getSimpleName());
        }

        // 2) 파생 비디오 파일 삭제 + 잔존 재확인 (원본 절대 미삭제 — 파생 목적 경로만).
        if (videoDst != null) {
            try {
                deleteFileQuietly(videoDst);
                if (Files.exists(videoDst)) {
                    clean = false;
                }
            } catch (RuntimeException e) {
                clean = false;
                log.warn("[Video][ResolutionDerivative][B] video file cleanup skipped newRawSn={} cause={}",
                        newRawSn, e.getClass().getSimpleName());
            }
        }
        return clean;
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort — 개별 파일 삭제 실패는 무시(상위에서 exists 재확인으로 잔존 판정).
                }
            });
        } catch (IOException ignored) {
            // best-effort — 디렉토리 순회 실패는 무시(상위에서 exists 재확인으로 잔존 판정).
        }
    }

    private static void deleteFileQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort — 삭제 실패는 무시(상위에서 exists 재확인으로 잔존 판정).
        }
    }

    /** 디렉토리 상대경로를 base 하위로 결정론적 해석 + normalize 검증 (CWE-22). */
    private Path resolveSafeDir(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }
}
