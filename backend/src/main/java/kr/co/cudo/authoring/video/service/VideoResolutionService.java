package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionChangeRequest;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse.CreatedDerivative;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 해상도 변경(RESOLUTION) 오케스트레이션 — Phase 3 (RQ-SFR-06-03 파생영상 전환).
 *
 * <p><b>정책 전환(R8)</b>: 구 "경량 export 1행 즉시 반환"을 폐기하고, 검수 완료(APPROVED) 원본 영상 1건에서
 * 표준 프리셋(RES_1080P/RES_720P/RES_480P)마다 <b>새 파생영상(RAW_SN)</b>을 만들어 검수 파이프라인에
 * 진입시킨다. 프리셋별 실제 예약·부모 락·PII 게이트·비동기 확정은 Phase 2
 * {@link ResolutionDerivativeService#createDerivative} 를 재사용한다(중복 구현 금지).
 *
 * <h3>오케스트레이션 규칙</h3>
 * <ul>
 *   <li><b>업스케일 허용</b>: 목표 해상도가 원본보다 커도 거부하지 않는다(구 업스케일 가드 제거).</li>
 *   <li><b>동일 해상도 스킵</b>: {@code preset.width()==srcW && preset.height()==srcH} 인 프리셋만
 *       scale=1 중복 회피로 건너뛴다(결과 목록에서 제외).</li>
 *   <li><b>프리셋별 부분 실패 격리</b>: 한 프리셋 생성 실패가 다른 프리셋·원본에 영향 없이 독립 예약/RAW 로
 *       처리되며, 실패 프리셋은 결과에 {@code FAILED} 로 표기된다.</li>
 *   <li>적용 가능한 프리셋이 0개면(모두 원본과 동일 해상도) {@link ErrorCode#INVALID_INPUT} 로 거부한다.</li>
 * </ul>
 *
 * <p><b>RBAC</b>: 권한 검증은 Controller {@code @PreAuthorize("hasRole('REVIEWER')")} 가 1차 책임이고,
 * 본 서비스는 비즈니스 규칙만 다룬다. 경로는 normalize + storageRawPath base 검증(CWE-22),
 * 로그/예외는 경로 hash 마스킹·추상 메시지(CWE-209)로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoResolutionService {

    /** 요구사항 고정 표준 프리셋 3종(1080/720/480). */
    private static final List<ResolutionPreset> STANDARD_PRESETS =
            List.of(ResolutionPreset.RES_1080P, ResolutionPreset.RES_720P, ResolutionPreset.RES_480P);

    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository statusRepository;
    private final LsDataSrcRepository srcRepository;
    private final ImageResizer imageResizer;
    private final ResolutionDerivativeService resolutionDerivativeService;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 해상도 변경 실행 — 검증(APPROVED·비-파생) → 원본 해상도 실측 → 동일 해상도 제외 프리셋마다
     * 파생영상 생성(부분 실패 격리).
     *
     * @param rawSn   원시 영상 PK (검수 완료 + 비-증강본만 허용)
     * @param request 생성할 프리셋 요청(선택). 미지정 시 표준 3종 전체가 대상이 된다.
     * @param regId   등록자(REVIEWER) 식별자 (감사 추적용)
     * @return 프리셋별 생성 결과(파생 RAW_SN + 목표 해상도 + 상태)
     */
    public ResolutionChangeResponse changeResolution(Long rawSn, ResolutionChangeRequest request, String regId) {
        // 1) 조회 + 검증 (APPROVED + 비-증강본).
        loadAndValidate(rawSn);

        // 2) 대상 프리셋 결정 — 미지정(기본)이면 표준 3종 전체, 지정 시 그 목록만(계약: optional list).
        List<ResolutionPreset> targetPresets =
                request == null ? STANDARD_PRESETS : request.resolvePresets(STANDARD_PRESETS);

        // 3) 첫 프레임 실측으로 원본 해상도 산정 — 동일 해상도 스킵 판정에 사용. 업스케일 거부 가드는 제거.
        int[] dim = measureFirstFrame(rawSn);
        int srcW = dim[0];
        int srcH = dim[1];
        if (srcW <= 0 || srcH <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
        }

        // 4) 동일 해상도 제외 프리셋마다 파생영상 생성 — 프리셋별 부분 실패 격리.
        List<CreatedDerivative> results = new ArrayList<>();
        for (ResolutionPreset preset : targetPresets) {
            if (preset.width() == srcW && preset.height() == srcH) {
                log.info("[Video][Resolution] preset skipped (same resolution) rawSn={} preset={} {}x{}",
                        rawSn, preset.name(), srcW, srcH);
                continue;
            }
            results.add(createOne(rawSn, preset, regId));
        }

        // 5) 적용 가능한 프리셋이 0개(모두 원본과 동일)면 거부.
        if (results.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "원본과 동일하지 않은 적용 가능한 해상도 프리셋이 없습니다.");
        }

        // 6) 시도한 프리셋이 하나도 CREATED 되지 못하고 전부 FAILED 면 성공(201)이 아닌 처리 실패로 응답한다.
        //    (부분 성공은 201 유지, 응답에 내부 사유 미노출 CWE-209 — 상세는 createOne 의 로그로만.)
        boolean anyCreated = results.stream()
                .anyMatch(d -> d.status() == ResolutionChangeResponse.DerivativeStatus.CREATED);
        if (!anyCreated) {
            log.error("[Video][Resolution] all presets failed rawSn={} attempted={}", rawSn, results.size());
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다.");
        }
        return new ResolutionChangeResponse(results);
    }

    /**
     * 프리셋 1건 파생영상 생성 — 실패는 격리하여 {@code FAILED} 결과로 흡수한다(다른 프리셋 진행 보장).
     */
    private CreatedDerivative createOne(Long rawSn, ResolutionPreset preset, String regId) {
        try {
            ResolutionDerivativeResponse d = resolutionDerivativeService.createDerivative(rawSn, preset, regId);
            return CreatedDerivative.created(d.newRawSn(), preset.name(), preset.width(), preset.height());
        } catch (RuntimeException e) {
            // 부분 실패 격리 — 실패 프리셋만 FAILED 로 표기, 상세(스택트레이스)는 내부 로그로만(응답에 사유 미노출 CWE-209).
            log.warn("[Video][Resolution] derivative creation failed rawSn={} preset={} reason={}",
                    rawSn, preset.name(), e.getClass().getSimpleName(), e);
            return CreatedDerivative.failed(preset.name(), preset.width(), preset.height());
        }
    }

    /**
     * 조회 + 비-증강본 + APPROVED 검증.
     *
     * <p>같은 클래스 내부 호출(self-invocation)이라 Spring 프록시가 트랜잭션 어드바이스를 적용하지 못하므로
     * {@code @Transactional} 을 두지 않는다(죽은 선언 제거). 단건 읽기 전용 검증이라 트랜잭션 경계가
     * 불필요하며, 실제 예약/생성의 잠금·트랜잭션은 별도 빈 {@link ResolutionReservationPersister} 가 담당한다.
     */
    public LsDataRaw loadAndValidate(Long rawSn) {
        LsDataRaw parent = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다: rawSn=" + rawSn));

        // 중첩 파생 거부 — 이미 증강/파생 산출물이면 원본 아님 (정책)
        if (parent.getOrgnlRawSn() != null) {
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

    /** 첫 프레임(FRM_NO 최소) 이미지의 실제 해상도 실측. PII-안전을 위해 비식별 프레임 우선. */
    private int[] measureFirstFrame(Long rawSn) {
        LsDataSrc first = srcRepository.findByRawSnAndFrameNo(rawSn, 0)
                .orElseGet(() -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn).stream()
                        .min(Comparator.comparing(LsDataSrc::getFrameNo))
                        .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT,
                                "실측할 프레임이 없습니다.")));
        Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path srcPath = resolveSafeFile(base, ResolutionDerivativeService.frameSourcePath(first));
        return imageResizer.readDimensions(srcPath);
    }

    /** 파일 경로 normalize + storageRawPath base 검증 (CWE-22). */
    private Path resolveSafeFile(Path base, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "원본 프레임 경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }
}
