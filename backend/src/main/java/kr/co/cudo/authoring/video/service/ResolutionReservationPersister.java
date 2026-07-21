package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.LsResolutionExport;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.runner.AsyncResolutionRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 해상도 파생영상 예약/생성 트랜잭션(동기부) — Phase 2.
 *
 * <p>별도 빈으로 분리하여 Spring 프록시가 {@code @Transactional} 경계를 실제 적용하도록 한다
 * (같은 클래스 내부 호출(self-invocation) 시 트랜잭션 어드바이스가 적용되지 않는 함정을 피하기 위함).
 *
 * <h3>단일 동기 트랜잭션이 담는 것 (그 외는 async 로)</h3>
 * <ol>
 *   <li>부모 RAW {@code findByRawSnForUpdate} 비관적 잠금</li>
 *   <li>{@code deIdntfYn=='Y'} PII 게이트 재검증 (CWE-359 TOCTOU)</li>
 *   <li>UNIQUE(DATA_RAW_SN, GOAL_RES_CD) 예약행 INSERT — 동시 (원본,해상도) 요청 직렬화</li>
 *   <li>새 RAW(PENDING) INSERT + EXPORT 에 newRawSn 연결</li>
 *   <li>AFTER_COMMIT 으로 {@link AsyncResolutionRunner} 비동기 확정 트리거</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResolutionReservationPersister {

    private final VideoRepository videoRepository;
    private final LsResolutionExportRepository exportRepository;
    private final AsyncResolutionRunner asyncResolutionRunner;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    @Transactional("controlTransactionManager")
    public Reservation reserveAndCreate(LsDataRaw parentSnapshot, ResolutionPreset preset,
                                        int srcW, int srcH, int targetW, int targetH, String regId) {
        // HIGH — 부모 RAW 를 PESSIMISTIC_WRITE 로 재조회·잠금. 게이트~커밋을 동시 비식별 신고와 직렬화한다.
        LsDataRaw parent = videoRepository.findByRawSnForUpdate(parentSnapshot.getRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다: rawSn=" + parentSnapshot.getRawSn()));

        // HIGH (CWE-359 PII TOCTOU) — 부모 비식별 완료('Y')를 잠금 하에서 재검증. 동시 신고가 'F' 를
        // 먼저 커밋했으면 여기서 관측하고 파생 생성을 거부한다(PII 파생본 차단).
        if (!"Y".equals(parent.getDeIdntfYn())) {
            log.warn("[Video][ResolutionDerivative] parent not deidentified — blocking derivative parentRawSn={} deIdntfYn={}",
                    parent.getRawSn(), safe(parent.getDeIdntfYn()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 완료된 원본 영상만 해상도 파생영상을 만들 수 있습니다.");
        }

        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
        String outputDir = resolveSafeDir(base, "resolution/" + parent.getRawSn() + "/" + preset.name()).toString();
        String derivativeVideoPath = resolveSafeDir(base,
                "resolution/" + parent.getRawSn() + "/" + preset.name() + "/video/" + preset.name() + ".mp4").toString();

        // UK(DATA_RAW_SN, GOAL_RES_CD) 조기 예약 — 새 RAW/파일 만들기 전에 INSERT + flush 로 위반 즉시 감지.
        LsResolutionExport export;
        try {
            export = exportRepository.save(LsResolutionExport.createReservation(
                    parent.getRawSn(), preset.name(), srcW, srcH, targetW, targetH, outputDir, regId));
            exportRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.");
        }

        LsDataRaw newRaw = videoRepository.save(
                LsDataRaw.createFromResolution(parent, derivativeVideoPath, preset.name()));
        export.attachNewRawSn(newRaw.getRawSn());

        triggerAsyncFinalizeAfterCommit(newRaw.getRawSn(), parent.getRawSn(), export.getResExportSn(), preset);
        return new Reservation(newRaw.getRawSn(), export.getResExportSn());
    }

    /** 새 RAW 커밋 이후에 비동기 확정을 트리거한다(커밋 전 호출 시 REQUIRES_NEW 가 새 RAW 를 못 봄). */
    private void triggerAsyncFinalizeAfterCommit(Long newRawSn, Long parentRawSn, Long resExportSn,
                                                 ResolutionPreset preset) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncResolutionRunner.runAsync(newRawSn, parentRawSn, resExportSn, preset);
                }
            });
        } else {
            asyncResolutionRunner.runAsync(newRawSn, parentRawSn, resExportSn, preset);
        }
    }

    /** 디렉토리/파일 상대경로를 base 하위로 결정론적 해석 + normalize 검증 (CWE-22). */
    private Path resolveSafeDir(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replaceAll("[\\r\\n\\t]", "_");
    }

    /** 예약 결과 — 새 RAW_SN + EXPORT_SN. */
    public record Reservation(Long newRawSn, Long resExportSn) {
    }
}
