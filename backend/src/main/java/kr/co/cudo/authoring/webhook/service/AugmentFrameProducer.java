package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 증강 프레임 재추출 확정 — <b>Phase B(파일 I/O)</b>. 커넥션-점유 분리 리팩터의 2단계(증강 경로).
 *
 * <p><b>{@code @Transactional} 절대 금지 · 리포지토리 주입 금지</b> — 순수 파일 서비스다. Phase A 가 확정한
 * {@link AugmentExtractPlan}(소스·산출 경로·프레임 번호 전부 포함)만 받아 증강 영상 파일에서 프레임을
 * ffmpeg 로 재추출해 <b>파일로만</b> 산출한다. DB 접근 0, 부모 잠금 0 이므로 이 대용량 I/O 구간에서
 * 커넥션을 점유하지 않는다(리팩터 목적). 파일 쓰기는 순수 포트 {@link FfmpegFrameExtractor.FrameWriter}
 * (ffmpeg 바이너리 추상화)로만 수행한다.
 *
 * <p>증강본은 원본에 마스킹만 한 것이 아니라 픽셀이 달라, 부모 프레임 이미지를 복사하지 않고 증강 파일에서
 * 동일 디코더 프레임 번호({@code videoFrameNo})를 새로 추출한다(frame-exact). 원본 1벌만 추출한다
 * (증강본은 RAW=DEID 동일 취급, 비식별 base 폴백 없음 — 구 {@code extractByFrameNumbers} 동작 보존).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentFrameProducer {

    private final FfmpegFrameExtractor.FrameWriter frameWriter;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 계획을 파일로 산출한다 — 증강 영상에서 프레임별 목표 번호를 frame-exact 재추출한다.
     * DB 접근·부모 잠금 없이 순수 파일 작업만 수행한다.
     *
     * <p><b>all-or-nothing</b>: 한 프레임이라도 IOException 이면 전체 실패({@code INTERNAL_ERROR})다. 러너가
     * catch 하여 부분 산출 파일을 {@link #cleanup} 로 정리하고 신규 RAW 를 FAILED 로 전이한다.
     *
     * @throws CustomException 증강 소스 파일 부재(INVALID_INPUT)·추출 실패(INTERNAL_ERROR)
     */
    public void produce(AugmentExtractPlan plan) {
        // 소스 파일 존재만 확인한다(deIdntfYn 게이트 미적용 — 순환 의존 차단, 구 동작 보존).
        if (!frameWriter.sourceExists(plan.sourceVideo())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 영상을 찾을 수 없습니다: rawSn=" + plan.newRawSn());
        }
        try {
            ensureDir(plan.framesDir());
            for (AugmentExtractPlan.FrameSpec f : plan.frames()) {
                // frame-exact 추출 — fps/seek 가정 없이 디코더 프레임 번호로 직접 1장 추출.
                frameWriter.writeFrameByNumber(plan.sourceVideo(), f.dst(), f.videoFrameNo().intValue());
            }
        } catch (IOException e) {
            // all-or-nothing — 러너가 cleanup 으로 부분 산출 파일을 정리한다.
            log.error("[Augment][ExtractB] frame re-extraction failed rawSn={} err={}",
                    plan.newRawSn(), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "증강 프레임 재추출 실패", e);
        }
        log.info("[Augment][ExtractB] produced rawSn={} frames={}", plan.newRawSn(), plan.frames().size());
    }

    /**
     * 실패 시 Phase B 산출 아티팩트(재추출 프레임 디렉토리 {@code frames/raw/{newRawSn}})를
     * <b>동기 삭제 후 {@link Files#exists} 재확인</b>한다. 잔존이 확인되면 {@code false} 를 반환하여
     * 러너가 ERROR 로그 + 메트릭({@code augment.cleanup.failed}) 을 남기게 한다.
     *
     * <p>신규(증강) RAW 경로만 삭제 대상이다 — 원본은 절대 삭제하지 않는다. 증강 소스 영상 파일 자체도
     * 외부 산출물이라 정리 대상이 아니다(본 Phase 는 프레임 이미지만 생성한다). 디렉토리는 {@code newRawSn}
     * 으로 재계산해 CWE-22 검증한다(구 산출 경로와 동일 규약).
     *
     * @param newRawSn 신규 증강 RAW_SN (프레임 디렉토리 {@code frames/raw/{newRawSn}/} 산정 기준)
     * @return 잔존 아티팩트 없음(정리 성공)=true, 삭제 후에도 잔존=false
     */
    public boolean cleanup(Long newRawSn) {
        if (newRawSn == null) {
            return true;
        }
        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
        try {
            Path framesDir = resolveSafeDir(base, "frames/raw/" + newRawSn);
            deleteRecursivelyQuietly(framesDir);
            return !Files.exists(framesDir);
        } catch (RuntimeException e) {
            log.warn("[Augment][ExtractB] frames dir cleanup skipped newRawSn={} cause={}",
                    newRawSn, e.getClass().getSimpleName());
            return false;
        }
    }

    private static void ensureDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
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

    /** 상대경로를 base 하위로 결정론적 해석 + normalize 검증 (CWE-22). */
    private Path resolveSafeDir(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }
}
