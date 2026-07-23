package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionDerivativeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

/**
 * 해상도 파생영상 적재 서비스(오케스트레이션) — Phase 2 (RQ-SFR-06-03 파생영상).
 *
 * <p>원본(APPROVED·비식별) 영상 1건 + 목표 해상도 프리셋 1개 → 새 파생영상(RAW_SN)을 만든다.
 * 증강 파이프라인({@code AugmentResultService}) 패턴을 재사용해 <b>동기 트랜잭션에서는 부모 안전 판정
 * + UK 예약 + 새 RAW(PENDING)만 커밋</b>({@link ResolutionReservationPersister})하고, 무거운 파일 복사/
 * 리스케일/라벨 복사는 커밋 후 비동기({@code AsyncResolutionRunner})로 미룬다.
 *
 * <h3>동기/비동기 경계 (반드시 준수)</h3>
 * <ul>
 *   <li>부모 {@code findByRawSnForUpdate} 잠금 + {@code deIdntfYn=='Y'} 게이트(CWE-359 PII TOCTOU)는
 *       {@link ResolutionReservationPersister} 의 동기 트랜잭션에서만 유효하다 — 절대 async 로 이동 금지.</li>
 *   <li>UNIQUE(DATA_RAW_SN, GOAL_RESL_CD) 예약행을 새 RAW/파일 생성 전에 INSERT 하여 (원본,해상도)
 *       동시요청을 DB 레벨에서 1건으로 직렬화한다.</li>
 *   <li>파일 I/O(비디오 복사·프레임 리스케일)와 라벨 복사는 동기 트랜잭션에 넣지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionDerivativeService {

    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository statusRepository;
    private final LsDataSrcRepository srcRepository;
    private final ImageResizer imageResizer;
    private final ResolutionReservationPersister reservationPersister;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 비식별 프레임 이미지 base 경로. 비식별 프레임({@code deidFilePath})은 deidentified-path 기준 절대경로라
     * PII-안전 실측 시 경로 검증 base 로 raw-path 뿐 아니라 deidentified-path 도 허용해야 한다. 선례:
     * PortalLabelService(R17 이슈1).
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 해상도 파생영상 생성 — 검증(APPROVED·비-파생) → 첫 프레임 실측(srcW/H>0) → UK 예약 +
     * 새 RAW(PENDING) 커밋(별도 빈) → AFTER_COMMIT 비동기 확정 트리거.
     *
     * @param parentRawSn 원본 RAW_SN (검수완료·비식별·ORGNL_RAW_SN=null)
     * @param preset      목표 해상도 프리셋
     * @param regId       등록자(REVIEWER) 식별자
     * @return 파생 RAW_SN + EXPORT_SN + 원본/목표 해상도 + 배율
     */
    public ResolutionDerivativeResponse createDerivative(Long parentRawSn, ResolutionPreset preset, String regId) {
        LsDataRaw parent = loadAndValidate(parentRawSn);

        // 첫 프레임 실측(트랜잭션 밖) — 대표프레임 SRC_SN + srcW/srcH 확보. 0/음수면 파생 거부(scale 0 division 방지).
        // #3 — 여기서 확정한 첫 프레임 SRC_SN 을 그대로 예약행(LS_DATA_AUG.SRC_SN)의 단일 기준으로 재사용한다.
        LsDataSrc firstFrame = firstFrame(parentRawSn);
        int[] dim = measureDimensions(firstFrame);
        int srcW = dim[0];
        int srcH = dim[1];
        if (srcW <= 0 || srcH <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
        }

        int targetW = preset.width();
        int targetH = preset.height();
        double scaleX = (double) targetW / srcW;
        double scaleY = (double) targetH / srcH;

        // 부모 잠금 + PII 게이트 + UK 예약 + 새 RAW(PENDING) 커밋 (별도 빈 = 프록시 트랜잭션 실제 적용).
        ResolutionReservationPersister.Reservation reservation =
                reservationPersister.reserveAndCreate(parent, preset, firstFrame.getSrcSn(), regId);

        log.info("[Video][ResolutionDerivative] derivative reserved parentRawSn={} newRawSn={} dataAugSn={} preset={} " +
                        "src={}x{} target={}x{}",
                parentRawSn, reservation.newRawSn(), reservation.dataAugSn(), preset.name(), srcW, srcH, targetW, targetH);

        return new ResolutionDerivativeResponse(
                reservation.newRawSn(), reservation.dataAugSn(),
                srcW, srcH, targetW, targetH, scaleX, scaleY);
    }

    /** 조회 + 비-파생(ORGNL_RAW_SN=null) + APPROVED 검증. 개별 repo 조회는 각자 트랜잭션(읽기)으로 동작. */
    public LsDataRaw loadAndValidate(Long parentRawSn) {
        LsDataRaw parent = videoRepository.findById(parentRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다: rawSn=" + parentRawSn));

        // 파생 체인 차단 — 이미 증강/파생 산출물이면 원본 아님(중첩 파생 방지).
        if (parent.getOrgnlRawSn() != null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 영상에만 해상도 파생영상 생성 가능");
        }

        boolean approved = statusRepository.findByRawDataIdIn(List.of(parentRawSn)).stream()
                .anyMatch(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()));
        if (!approved) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "검수 완료(APPROVED)된 영상만 해상도 파생영상을 만들 수 있습니다.");
        }
        return parent;
    }

    /** 대표프레임(FRM_NO 최소) — 예약행 SRC_SN 과 실측 해상도의 단일 기준. */
    private LsDataSrc firstFrame(Long parentRawSn) {
        return srcRepository.findByRawSnAndFrameNo(parentRawSn, 0)
                .orElseGet(() -> srcRepository.findByRawSnOrderByFrameNoAsc(parentRawSn).stream()
                        .min(Comparator.comparing(LsDataSrc::getFrameNo))
                        .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT,
                                "파생할 프레임이 없습니다.")));
    }

    /** 프레임 이미지의 실제 해상도 실측. PII-안전을 위해 비식별 프레임 우선. */
    private int[] measureDimensions(LsDataSrc frame) {
        Path srcPath = resolveSafeSource(frameSourcePath(frame));
        return imageResizer.readDimensions(srcPath);
    }

    /** 리사이즈 원본 = 비식별 프레임 우선(deIdntfSrcFilePathNm), 없으면 원본 프레임(srcFilePathNm). */
    static String frameSourcePath(LsDataSrc frame) {
        String deid = frame.getDeidFilePath();
        if (deid != null && !deid.isBlank()) {
            return deid;
        }
        return frame.getSrcFilePathNm();
    }

    /**
     * 프레임 소스(원본 또는 비식별) 경로 normalize + base 검증 (CWE-22 traversal 가드).
     *
     * <p>비식별 프레임은 deidentified-path 기준 절대경로이므로 raw-path·deidentified-path 두 base 중 하나에
     * 속하면 통과시킨다. 두 base 모두 벗어나는 경로(상위 traversal 포함)는 여전히 거부한다. 상대경로는 기존과
     * 동일하게 raw base 기준으로 해석한다.
     */
    private Path resolveSafeSource(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "원본 프레임 경로가 비어있습니다.");
        }
        Path rawBase = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path deidBase = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : rawBase.resolve(candidate).normalize();
        if (!resolved.startsWith(rawBase) && !resolved.startsWith(deidBase)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }
}
