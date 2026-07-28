package kr.co.cudo.authoring.webhook.service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 증강 프레임 확정 계획 — Phase A({@link AugmentExtractSnapshot}) 가 확정한 <b>불변 값 묶음</b>.
 *
 * <p>커넥션-점유 분리 리팩터(락-I/O HIGH 증강 경로)의 핵심 계약: Phase A 가 DB·경로를 전부 스냅샷하여
 * 이 record 로 넘기면, Phase B({@link AugmentFrameProducer}, 파일 I/O)는 리포지토리·트랜잭션·커넥션 없이
 * 파일 작업만 수행할 수 있다(무커넥션). 여기 담기지 않은 DB 값을 Phase B 가 다시 조회하면 리팩터
 * 목적이 깨지므로, Phase B 가 필요로 하는 <b>모든</b> 경로를 Phase A 가 확정해 담는다.
 *
 * <p><b>Phase 7-D — 외부 산출 프레임 실반영</b>: 프레임 픽셀은 이제 부모 영상에서 재추출하지 않고
 * 외부 생성형 AI 가 반환한 산출 이미지({@code results[].output_file_path})를 그대로 반입한다. 부모
 * 재추출은 증강 효과가 0 인 사본을 만들었기 때문이다(원본 픽셀 그대로면 WINTER/NIGHT/RAIN 라벨만
 * 붙은 가짜 파생영상이 된다). 따라서 계획은 영상 소스가 아니라 <b>프레임별 외부 산출 경로</b>를 든다.
 *
 * <p><b>비디오 파일은 부모 비식별본 복사</b>: 증강 AI 는 이미지-to-이미지라 영상을 재생성하지 않으므로,
 * 파생영상의 비디오는 <b>부모의 비식별 영상을 파생 전용 경로로 복사</b>해 만든다(해상도 파생
 * {@code ResolutionSnapshot} 과 동일 규약). 소스 경로는 조합·추측하지 않고
 * {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM} 값을 그대로 쓴다.
 *
 * <p>부모 잠금·비식별 재검증은 여기서 하지 않는다(동기 {@code AugmentResultService.handle} 의 부모
 * 안전 판정 이후 창을 재개방하지 않기 위함) — 이 자세는 유지한다.
 *
 * @param newRawSn       증강 신규 RAW_SN (동기 handle 에서 PENDING·deIdntfYn='N' 으로 커밋됨)
 * @param parentRawSn    원본(부모) RAW_SN — Phase C 메타 복사 소스
 * @param dataAugSn      LS_DATA_AUG PK — Phase C 라벨 매핑(LS_DATA_AUG_LBL_MAP) 적재용
 * @param regId          등록자(aug.regUserNo) — 라벨맵 등록자
 * @param referenceFrame 해상도 기준 실측 소스 — <b>부모 비식별 프레임 1건</b>(= 외부에 위탁했던 입력).
 *                       외부 산출물이 이 해상도와 다르면 라벨 좌표 그대로 복사가 무효이므로 fail-closed
 * @param deidVideoSrc   복사 소스 — <b>부모 비식별 영상</b> 절대경로(procLog 값, CWE-22 검증 완료).
 *                       원본(비-비식별) 영상은 절대 소스가 되지 않는다(PII, CWE-359)
 * @param videoDst       파생 비디오 목적 경로({@code {deidBase}/videos/augment/{parentRawSn}/{newRawSn}/…},
 *                       CWE-22 검증 완료) — 부모 파일과 절대 겹치지 않으며 cleanup 대상이다
 * @param framesDir      프레임 산출 디렉토리({@code {deidBase}/frames/deid/{newRawSn}}, CWE-22 검증 완료) — cleanup 기준
 * @param frames         프레임별 확정 스펙(부모 SRC_SN·추출순번·디코더 프레임번호·촬영일시·외부 산출 경로·산출 경로)
 */
public record AugmentExtractPlan(
        Long newRawSn,
        Long parentRawSn,
        Long dataAugSn,
        String regId,
        Path referenceFrame,
        Path deidVideoSrc,
        Path videoDst,
        Path framesDir,
        List<FrameSpec> frames) {

    /**
     * 프레임 1건의 확정 스펙 — Phase B 반입 입출력 + Phase C DB INSERT/라벨 재매핑 재료를 모두 담는다.
     *
     * @param parentSrcSn     부모 프레임 SRC_SN (라벨 재매핑 키 + 외부 산출물 대응 키)
     * @param frameNo         추출 순번(FRM_NO, 0-base)
     * @param videoFrameNo    디코더 프레임 번호(VDO_FRM_NO) — 부모와 동일 값을 실어 라벨 재매핑 정합을 유지한다.
     *                        Phase A 가 부모 프레임의 videoFrameNo(없으면 frameNo 폴백)를 확정해 담는다(non-null).
     * @param shtDt           촬영 일시(신규 프레임에 실을 값 = newRaw.shtDt)
     * @param externalSource  외부 생성형 AI 산출 이미지 경로(반입 소스) — Phase B 가 허용루트·실재·해상도 검증 후 복사
     * @param dst             프레임 산출 경로({@code {framesDir}/frame-{frameNo}.{ext}}, CWE-22 검증 완료)
     */
    public record FrameSpec(
            Long parentSrcSn,
            long frameNo,
            Long videoFrameNo,
            LocalDateTime shtDt,
            Path externalSource,
            Path dst) {
    }
}
