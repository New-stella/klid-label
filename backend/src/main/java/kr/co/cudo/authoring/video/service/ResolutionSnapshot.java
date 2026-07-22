package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.dto.ResolutionPreset;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 해상도 파생 확정 스냅샷 — Phase A(검증·잠금) 가 확정한 <b>불변 값 묶음</b>.
 *
 * <p>락-I/O 분리 리팩터(HIGH)의 핵심 계약: Phase A 가 부모 잠금 하에서 DB·경로를 전부 스냅샷하여
 * 이 record 로 넘기면, Phase B(파일 I/O)는 리포지토리·트랜잭션·부모 잠금 없이 순수 파일 작업만
 * 수행할 수 있다(#6 무잠금·무커넥션). 여기 담기지 않은 DB 값을 Phase B 가 다시 조회하면 리팩터 목적이
 * 깨지므로, Phase B 가 필요로 하는 <b>모든</b> 치수·경로를 Phase A 가 확정해 담는다.
 *
 * <p>담는 것: 파생/원본 RAW_SN·증강행 SN·프리셋, 원본/목표 치수·배율, 등록자, 비식별 비디오 원본 경로,
 * 파생 비디오 목적 경로, 프레임별 스펙 목록(비식별 원본 경로 + 목표 경로 + frameNo/videoFrameNo/촬영일시),
 * 그리고 스냅샷 캡처 시각({@code capturedAt}).
 * 모든 경로는 Phase A 에서 CWE-22 정규화·base 검증을 통과한 절대 경로다.
 *
 * <p><b>capturedAt (HIGH·CWE-359 stale 창 게이트)</b>: Phase A 가 부모 {@code deIdntfYn=='Y'} 를 잠금 하에
 * 확정한 시각. Phase C 재검증에서 "스냅샷 이후 부모 비식별본이 재비식별로 교체됐는가"를 결정적으로 판정하는
 * 기준 시각이다. Phase A~C 무잠금 창에서 부모가 신고→재비식별→resolve 로 'F'→'Y' 복원되며 비식별 파일이
 * 교체되면, Phase B 는 이미 구버전(PII) 픽셀을 복사했으므로 {@code deIdntfYn=='Y'} 만으로는 안전하지 않다.
 */
public record ResolutionSnapshot(
        Long newRawSn,
        Long parentRawSn,
        Long dataAugSn,
        ResolutionPreset preset,
        int srcW,
        int srcH,
        int targetW,
        int targetH,
        double scaleX,
        double scaleY,
        String regId,
        Path deidVideoSrc,
        Path videoDst,
        Instant capturedAt,
        List<FrameSpec> frames) {

    /**
     * 프레임 1건의 확정 스펙 — Phase B 리스케일 입출력 + Phase C DB INSERT 재료를 모두 담는다.
     *
     * @param parentSrcSn  부모 프레임 SRC_SN (라벨 재매핑 키)
     * @param frameNo      추출 순번(FRM_NO)
     * @param videoFrameNo 실제 영상 프레임 위치(nullable) — 명시 키 매핑용
     * @param shtDt        촬영 일시
     * @param deidSrc      비식별 원본 프레임 경로(검증 완료) — PII 안전, 원본(비-비식별) 경로 금지
     * @param dst          리스케일 산출 경로(검증 완료)
     */
    public record FrameSpec(
            Long parentSrcSn,
            Long frameNo,
            Long videoFrameNo,
            LocalDateTime shtDt,
            Path deidSrc,
            Path dst) {
    }
}
