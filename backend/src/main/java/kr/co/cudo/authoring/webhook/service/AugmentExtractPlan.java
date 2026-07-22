package kr.co.cudo.authoring.webhook.service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 증강 프레임 재추출 확정 계획 — Phase A({@link AugmentExtractSnapshot}) 가 확정한 <b>불변 값 묶음</b>.
 *
 * <p>커넥션-점유 분리 리팩터(락-I/O HIGH 증강 경로)의 핵심 계약: Phase A 가 DB·경로를 전부 스냅샷하여
 * 이 record 로 넘기면, Phase B({@link AugmentFrameProducer}, 파일 I/O)는 리포지토리·트랜잭션·커넥션 없이
 * ffmpeg 추출만 수행할 수 있다(무커넥션). 여기 담기지 않은 DB 값을 Phase B 가 다시 조회하면 리팩터
 * 목적이 깨지므로, Phase B 가 필요로 하는 <b>모든</b> 경로·프레임 번호를 Phase A 가 확정해 담는다.
 *
 * <p>기존 {@code AugmentFrameExtractionService.extractAndCopy} 와 동일하게, 이번 변경은 <b>순수 커넥션
 * 분리</b>다 — 부모 잠금·비식별 재검증은 여기서 하지 않는다(동기 {@code AugmentResultService.handle} 의
 * 부모 안전 판정 이후 창을 재개방하지 않기 위함). 즉 부모 PII 재검증 게이트를 이 record/Phase 에 이식하지
 * 않는다.
 *
 * @param newRawSn    증강 신규 RAW_SN (동기 handle 에서 PENDING·deIdntfYn='N' 으로 커밋됨)
 * @param parentRawSn 원본(부모) RAW_SN — Phase C 메타 복사 소스
 * @param dataAugSn   LS_DATA_AUG PK — Phase C 라벨 매핑(LS_DATA_AUG_LBL_MAP) 적재용
 * @param regId       등록자(aug.regUserNo) — 라벨맵 등록자
 * @param sourceVideo 증강 영상 파일 경로(재추출 소스) — Phase B 가 sourceExists 확인 후 프레임 추출
 * @param framesDir   프레임 산출 디렉토리({@code {base}/frames/raw/{newRawSn}}, CWE-22 검증 완료) — cleanup 기준
 * @param frames      프레임별 확정 스펙(부모 SRC_SN·추출순번·디코더 프레임번호·촬영일시·산출 경로)
 */
public record AugmentExtractPlan(
        Long newRawSn,
        Long parentRawSn,
        Long dataAugSn,
        String regId,
        Path sourceVideo,
        Path framesDir,
        List<FrameSpec> frames) {

    /**
     * 프레임 1건의 확정 스펙 — Phase B 추출 출력 + Phase C DB INSERT/라벨 재매핑 재료를 모두 담는다.
     *
     * @param parentSrcSn  부모 프레임 SRC_SN (라벨 재매핑 키)
     * @param frameNo      추출 순번(FRM_NO, 0-base)
     * @param videoFrameNo 디코더 프레임 번호(VDO_FRM_NO) — 증강 파일에서 재추출할 번호 + 라벨 재매핑 키.
     *                     Phase A 가 부모 프레임의 videoFrameNo(없으면 frameNo 폴백)를 확정해 담는다(non-null).
     * @param shtDt        촬영 일시(신규 프레임에 실을 값 = newRaw.shtDt)
     * @param dst          프레임 산출 경로({@code {framesDir}/frame-{frameNo}.jpg}, CWE-22 검증 완료)
     */
    public record FrameSpec(
            Long parentSrcSn,
            long frameNo,
            Long videoFrameNo,
            LocalDateTime shtDt,
            Path dst) {
    }
}
