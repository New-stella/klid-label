package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
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
 * 해상도 파생영상 예약/생성 트랜잭션(동기부) — Phase 1 (증강 저장모델 통합).
 *
 * <p>별도 빈으로 분리하여 Spring 프록시가 {@code @Transactional} 경계를 실제 적용하도록 한다
 * (같은 클래스 내부 호출(self-invocation) 시 트랜잭션 어드바이스가 적용되지 않는 함정을 피하기 위함).
 *
 * <h3>단일 동기 트랜잭션이 담는 것 (그 외는 async 로)</h3>
 * <ol>
 *   <li>부모 RAW {@code findByRawSnForUpdate} 비관적 잠금</li>
 *   <li>부모 비식별 산출물 존재({@code hasDeidentArtifact()}: 'Y'|'F') 재검증 — 신고('F')는 통과</li>
 *   <li>{@code LS_DATA_AUG}(SRC_SN, AUG_TYPE_CD='RESL_*') <b>PENDING</b> 행 INSERT — 부분 유니크
 *       인덱스(UK_LS_DATA_AUG_RESL)로 동시 (원본,해상도) 요청을 직렬화. 구 LS_RESOLUTION_EXPORT
 *       예약을 대체(증강 이력에 노출). 상태는 파생 생성 라이프사이클과 일치하며 finalize 성공 시
 *       ACCEPTED 로 전이된다.</li>
 *   <li>새 RAW(PENDING) INSERT</li>
 *   <li>AFTER_COMMIT 으로 {@link AsyncResolutionRunner} 비동기 확정 트리거</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResolutionReservationPersister {

    private final VideoRepository videoRepository;
    private final LsDataAugRepository augRepository;
    private final AsyncResolutionRunner asyncResolutionRunner;

    /**
     * 파생 영상(비식별본 복사) 출력 base — <b>비식별 저장소</b>.
     *
     * <p>E-ISSUE-21/B-ISSUE-61: 구현은 원래 raw base 하위에 파생 비디오를 만들고 그 경로를
     * {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM} 으로 기록했다. 스트리밍 가드는 비식별 base 만
     * 허용하므로 파생영상이 전면 403 이 됐고, 데이터마트는 raw 저장소 경로를 "비식별 경로"로 수신했다.
     * 파생 산출물은 비식별 산출물이므로 출력 base 자체를 비식별 저장소로 바로잡는다(스트리밍 가드는 불변).
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * @param parentSnapshot 원본 RAW (트랜잭션 밖 스냅샷 — 내부에서 잠금 재조회)
     * @param preset         목표 해상도 프리셋
     * @param firstFrameSrcSn 대표프레임 SRC_SN(= {@code measureFirstFrame} 첫 프레임, 단일 기준)
     * @param regId          등록자(REVIEWER) 식별자
     */
    @Transactional("controlTransactionManager")
    public Reservation reserveAndCreate(LsDataRaw parentSnapshot, ResolutionPreset preset,
                                        Long firstFrameSrcSn, String regId) {
        // HIGH — 부모 RAW 를 PESSIMISTIC_WRITE 로 재조회·잠금. 게이트~커밋을 동시 비식별 신고와 직렬화한다.
        LsDataRaw parent = videoRepository.findByRawSnForUpdate(parentSnapshot.getRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다: rawSn=" + parentSnapshot.getRawSn()));

        // 부모 비식별 산출물 존재 전제를 잠금 하에서 재검증한다({@link LsDataRaw#hasDeidentArtifact()}).
        //
        // ★ 비식별 누락 신고('F')는 여기서 막지 않는다 (2026-07-29 확정) — "파생영상은 비식별 신고 체계
        //   바깥" 정책과 대칭이다. 해상도 파생은 외부 위탁이 전혀 없는 내부 ffmpeg 리스케일뿐이라
        //   신고 구간에 생성해도 외부 유출 경로가 열리지 않는다. 신고가 실제로 막아야 하는 외부 위탁
        //   차단은 증강 요청/전송 진입점이 담당한다.
        // 차단 대상은 'N'(비식별 미수행)·null 하나다 — 복사할 비식별 산출물이 물리적으로 없다.
        if (!parent.hasDeidentArtifact()) {
            log.warn("[Video][ResolutionDerivative] parent has no deident artifact — blocking derivative parentRawSn={} deIdntfYn={}",
                    parent.getRawSn(), safe(parent.getDeIdntfYn()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 산출물이 있는 원본 영상만 해상도 파생영상을 만들 수 있습니다.");
        }

        // MED #4 — 대표프레임 SRC_SN 은 LS_DATA_AUG.SRC_SN(NOT NULL). null 이면 DB 제약이 트랜잭션 중간에
        // 터지기 전에 fail-fast(INSERT 이전) 로 거부한다.
        if (firstFrameSrcSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "대표 프레임을 확인할 수 없습니다.");
        }

        Path base = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();

        // UK(SRC_SN, AUG_TYPE_CD='RESL_*') 조기 예약 — 새 RAW/파일 만들기 전에 INSERT + flush 로 위반 즉시 감지.
        // 예약행은 PENDING(생성 중, non-terminal)으로 커밋한다 — finalize 성공 시에만 ACCEPTED(생성 완료)로
        // 전이하여 in-flight 창에서 집계가 조기 COMPLETED 로 오표기되지 않게 한다(라이프사이클 정합).
        LsDataAug aug;
        try {
            aug = augRepository.save(
                    LsDataAug.createResolutionPending(firstFrameSrcSn, preset.name(), regId));
            augRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.");
        }

        // A-6 — 파생 영상 파일 경로 키에 <b>파생 RAW_SN</b> 을 포함한다. RAW_SN 은 INSERT(IDENTITY) 이후에만
        //       알 수 있으므로 ①부모·프리셋만으로 만든 잠정 경로로 INSERT(NOT NULL 충족) → ②확정 RAW_SN 으로
        //       최종 경로를 배정한다. 같은 트랜잭션이라 잠정값은 외부에 커밋·관측되지 않으며, LS_DATA_RAW 는
        //       @DynamicUpdate 라 UPDATE 는 RAW_FILE_PATH_NM 한 컬럼만 건드린다(다른 writer 와 무충돌).
        LsDataRaw newRaw = videoRepository.save(LsDataRaw.createFromResolution(
                parent, provisionalVideoPath(base, parent.getRawSn(), preset), preset.name()));
        String derivativeVideoPath = resolveSafeDir(base, StorageSubtreePolicy.resolutionVideoFile(
                parent.getRawSn(), newRaw.getRawSn(), preset.name())).toString();
        newRaw.assignDerivativeVideoPath(derivativeVideoPath);

        triggerAsyncFinalizeAfterCommit(newRaw.getRawSn(), parent.getRawSn(), aug.getDataAugSn(), preset);
        return new Reservation(newRaw.getRawSn(), aug.getDataAugSn());
    }

    /**
     * INSERT 시점 잠정 경로 — RAW_FILE_PATH_NM 이 NOT NULL 이라 필요한 자리표시자다. 최종 경로는
     * 확정 RAW_SN 이 붙은 {@link StorageSubtreePolicy#resolutionVideoFile(long, long, String)} 으로
     * 같은 트랜잭션 안에서 즉시 교체된다(잠정값이 커밋되는 경로는 존재하지 않는다).
     */
    private String provisionalVideoPath(Path base, Long parentRawSn, ResolutionPreset preset) {
        return resolveSafeDir(base, StorageSubtreePolicy.SEG_VIDEOS + "/"
                + StorageSubtreePolicy.SEG_RESOLUTION + "/" + parentRawSn
                + "/.pending/" + preset.name() + ".mp4").toString();
    }

    /** 새 RAW 커밋 이후에 비동기 확정을 트리거한다(커밋 전 호출 시 REQUIRES_NEW 가 새 RAW 를 못 봄). */
    private void triggerAsyncFinalizeAfterCommit(Long newRawSn, Long parentRawSn, Long dataAugSn,
                                                 ResolutionPreset preset) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncResolutionRunner.runAsync(newRawSn, parentRawSn, dataAugSn, preset);
                }
            });
        } else {
            asyncResolutionRunner.runAsync(newRawSn, parentRawSn, dataAugSn, preset);
        }
    }

    /**
     * 디렉토리/파일 상대경로를 base 하위로 결정론적 해석 + normalize + 비식별 서브트리 검증 (CWE-22).
     *
     * <p>두 저장소 base 가 동일 경로인 운영 환경에서도 파생 산출물이 원본 서브트리로 새지 않도록
     * {@link StorageSubtreePolicy} 규약({@code videos/**})까지 함께 강제한다.
     */
    private Path resolveSafeDir(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "출력 경로가 허용된 비식별 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replaceAll("[\\r\\n\\t]", "_");
    }

    /** 예약 결과 — 새 RAW_SN + 증강행 DATA_AUG_SN. */
    public record Reservation(Long newRawSn, Long dataAugSn) {
    }
}
