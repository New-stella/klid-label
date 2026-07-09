package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.LsResolutionExport;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 해상도 변경 산출 추적 적재 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>{@link VideoResolutionService} 가 프레임 이미지셋 다운스케일(트랜잭션 밖)을 모두 마친 후
 * 호출한다. 별도 빈으로 분리하여 Spring 프록시가 {@code REQUIRES_NEW} 트랜잭션 경계를 실제
 * 적용하도록 한다(self-invocation 시 트랜잭션 미적용 함정 회피).
 *
 * <p><b>정책 변경(R1)</b>: LsDataRaw·프레임·라벨·속성값·메타 복사를 전부 제거하고
 * {@link LsResolutionExport} 1행만 INSERT 한다. 신규 영상(RAW_SN)·라벨·메타는 생성하지 않는다.
 * 동시 요청 UK 경합은 DB UNIQUE(DATA_RAW_SN, GOAL_RES_CD)로 최종 방어한다(HIGH-②).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoResolutionPersister {

    private final LsResolutionExportRepository exportRepository;

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public ResolutionChangeResponse persist(LsDataRaw parent, ResolutionPreset preset, String outputDirPath,
                                            int srcW, int srcH, int targetW, int targetH,
                                            int frameCount, String regId) {
        LsResolutionExport saved = exportRepository.save(LsResolutionExport.create(
                parent.getRawSn(), preset.name(), srcW, srcH, targetW, targetH,
                frameCount, outputDirPath, regId));

        log.info("[Video][Resolution] export recorded exportSn={} rawSn={} preset={} " +
                        "src={}x{} target={}x{} frames={}",
                saved.getResExportSn(), parent.getRawSn(), preset.name(),
                srcW, srcH, targetW, targetH, frameCount);

        return new ResolutionChangeResponse(
                saved.getResExportSn(), srcW, srcH, targetW, targetH, frameCount);
    }
}
