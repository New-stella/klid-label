package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 영상 상세 응답 — 상세 화면용.
 * <p>
 * BE 원본 컬럼 외 FE 호환 alias 필드를 함께 노출 (id/cctvName/eventTypeCd 등).
 * 자세한 매핑 규칙은 {@link VideoSummaryResponse} 참고.
 */
public record VideoDetailResponse(
        // FE 호환 alias
        Long id,
        String cctvName,
        String eventName,
        String eventTypeCd,
        String localGov,
        Long frameCount,
        String status,
        // 검수 상태 — LS_RAW_DATA_STATUS.DATA_STTS_CD (APPROVED=검수완료). 상태 row 없으면 null.
        // status(=배치단계 LS_DATA_RAW.DATA_STTS_CD)와 출처·의미가 다른 별도 필드다.
        String reviewSttsCd,
        // BE 원본 필드
        Long rawSn,
        String vmsClipId,
        String vmsCctvId,
        String evntTypeCd,
        String lclgvCd,
        String prvcTypeCd,
        String prvcYn,
        String deIdntfYn,
        String filePath,
        LocalDateTime capturedAt,
        Integer durationSec,
        String dataSttsCd,
        LocalDateTime regDt,
        LocalDateTime updDt,
        // 프레임 미리보기 (최대 6개)
        List<FramePreviewDto> framePreviews,
        // 배치 파이프라인 단계별 진행 상태 (이슈1). canonical 순서(비식별~보간).
        // 배치 미진행/기존 영상이면 빈 배열 → FE 가 기존 배지로 폴백(하위호환). PII/경로/스택 미포함.
        List<StageStatusDto> stages,
        /*
         * C-ISSUE-01 / DEV_FIX(H10) — 영상 실 프레임레이트(video.fps, 미상 시 서버 폴백 30.0).
         *
         * FE 마킹 화면이 frameIndex = round(currentTime × fps) 를 계산할 때 <b>서버와 같은 fps</b> 를
         * 쓰게 하려고 노출한다. FE 가 30 을 하드코딩하던 동안, 서버의 마킹 상한(=round(길이×실 fps))은
         * 실 fps 로 계산되어 25fps 영상에서는 영상 뒤 16.7% 구간의 정상 마킹이 400 으로 거부됐다
         * (25fps·60초 상한 1500 vs FE 가 만든 55×30=1650). 진실원은 서버의
         * {@code VideoFpsResolver} 하나이며 FE 는 그 값을 그대로 사용한다.
         */
        Double fps,
        /*
         * 파생영상(증강 WINTER/NIGHT/RAIN · 해상도 RESL_*) 여부 — 판정 원천은 LsDataRaw.isDerivative()
         * (= ORGNL_RAW_SN 보유, 생성 후 불변).
         *
         * 화면이 <b>비식별 누락 신고 버튼을 미리 비활성화</b>하기 위해 필요하다. 파생영상은 신고 체계
         * 바깥이라 BE 가 412 로 거부하는데(DeidentReportService.requireReportableVideo), 이 값이 없으면
         * 사용자는 사유를 다 적어 제출한 뒤에야 거부를 알게 된다. 부모 rawSn 은 <b>내려주지 않는다</b> —
         * 원본을 신고해도 이 파생영상은 달라지지 않으므로 원본으로 유도하는 것 자체가 잘못된 안내다.
         */
        boolean derivative,
        /*
         * 비식별 이력 — 요청일시 내림차순(최신 먼저). [req: R14]
         *
         * 원천은 LS_DEIDENT_PROC_LOG 다. 이 테이블은 위탁 <회차마다 새 행을 INSERT> 하므로(최초 배치
         * 비식별 + 재비식별 재위탁) 그 행들이 곧 이력이다 — 별도 이력 테이블을 두지 않는다.
         *
         * 이력이 없거나 구 데이터면 빈 배열이다(null 아님). 기존 from(...) 오버로드로 만든 응답도
         * 빈 배열이라, 이 필드가 추가돼도 기존 소비자는 영향받지 않는다(추가만 — 하위호환).
         */
        List<DeidentHistoryDto> deidentHistory,
        /*
         * P2b — 이 영상이 <b>한번이라도</b> 검수 완료된 적이 있는가(지금 상태가 아니라 이력).
         *
         * 화면이 <b>비식별 누락 신고 버튼과 프레임 폐기·복원 조작을 미리 비활성화</b>하기 위해 필요하다.
         * BE 가 각각 412/400 으로 거부하는데(DeidentReportService.requireNotApprovedVideo ·
         * LabelService.requireDiscardAllowed), 이 값이 없으면 사용자는 사유를 다 적어 제출하거나 폐기를
         * 누른 뒤에야 거부를 알게 된다.
         *
         * ⚠ reviewSttsCd(현재 상태)와 <b>다른 축</b>이다 — 재검수 재제출로 상태가 PENDING 으로 내려간
         * 구간에도 이 값은 true 다. 화면이 reviewSttsCd 로 대신 판정하면 그 구간에서 버튼이 열린다.
         *
         * 기존 from(...) 오버로드로 만든 응답은 false 이므로 이 필드가 추가돼도 기존 소비자는
         * 영향받지 않는다(추가만 — 하위호환). [req: P2b]
         */
        boolean everApproved,
        /*
         * 배치 실패 사유 — <b>사용자에게 보여줄 문구</b>. 실패가 아니면 null. [@design API-043]
         *
         * 화면이 "실패" 배지만 보여 주면 운영자는 무엇 때문에 멈췄는지 알 수 없어 재기동/스킵 중 어느
         * 것을 골라야 할지 판단할 수 없다. 그렇다고 내부 원문(LS_BATCH_PROC_LOG.ERR_MSG_CN)을 그대로
         * 내리면 DB 제약명·SQL·경로가 새므로(CWE-209), 변환 판정 단일 지점인
         * BatchFailureReasonPolicy 가 만든 상수 문구만 담는다.
         *
         * ★ <b>단계 배열(stages)이 아니라 영상 단위 필드</b>인 이유: 단계를 특정할 수 없는 실패
         * (PROC_STEP_CD='FAILED')는 BatchStageProgressMapper.build 가 빈 배열을 주므로, 사유를 단계
         * 안에 넣었다면 그 영상은 사유를 아예 볼 수 없다(dev 실측상 원본 실패 3건 중 1건이 이 형태).
         *
         * 기존 from(...) 오버로드로 만든 응답은 null 이므로 이 필드가 추가돼도 기존 소비자는
         * 영향받지 않는다(추가만 — 하위호환).
         */
        String batchFailureReason,
        /*
         * 검수자가 건너뛴 <b>작업 묶음</b> 목록 — VLM(시계열) / AUTOLABEL(AI 탐지·AI 분할·트랙 보간).
         * [@design API-043]
         *
         * ★ 값의 단위가 개별 단계가 아니라 묶음이다(구 VLM/YOLO/SAM2 3종 폐기). 오토라벨은 뒤 작업이 앞
         * 결과를 입력으로 받고 보간이 그 산출물을 재계산해 쪼개면 산출물끼리 어긋나며, 보간을 묶음 밖에
         * 두면 어떤 재수행에서도 보간이 무조건 돌아 사람이 손댄 보간 라벨을 지운다. 필드명은 하위호환으로
         * 유지한다.
         *
         * 건너뛴 묶음이 없으면 <b>빈 배열</b>이다(null 아님). 화면은 이 값으로 스킵 표시와 되돌리기
         * 조작의 노출을 정한다.
         *
         * ★ stages 로는 대체할 수 없다: 건너뛴 묶음은 markStage 를 타지 않고 표식 행도 진행 조회에서
         * 제외되므로 <b>진행 축에 흔적을 남기지 않는다</b>. 이 필드가 없으면 화면은 어느 묶음이
         * 스킵됐는지 알 방법이 전혀 없어 되돌리기 버튼을 띄울 근거가 없다.
         *
         * 순서는 묶음 선언 순서(VLM → AUTOLABEL) 고정 — 실행마다 흔들리면 화면이 깜빡인다.
         *
         * 기존 from(...) 오버로드로 만든 응답은 빈 배열이므로 이 필드가 추가돼도 기존 소비자는
         * 영향받지 않는다(추가만 — 하위호환).
         */
        List<String> skippedStages
) {
    /** 프레임 미리보기 항목 — srcSn으로 라벨링 도구 진입, thumbnailUrl로 이미지 표시. */
    public record FramePreviewDto(Long srcSn, Integer frameNo, String thumbnailUrl) {}

    /** 배치 단계 상태 — name=단계코드(DEIDENTIFY 등), status=DONE/PROGRESS/PENDING/FAIL, progress=nullable. */
    public record StageStatusDto(String name, String status, Integer progress) {}

    /**
     * 비식별 이력 1건 = {@code LS_DEIDENT_PROC_LOG} 1행(= 위탁 1회차). [req: R14]
     *
     * <p><b>파일 경로를 싣지 않는다</b>: 원본·비식별 산출물·리포트 경로는 모두 개인정보가 있는
     * 자산의 위치를 특정하는 정보다(CWE-359). 화면이 필요로 하는 것은 "언제 무엇을 얼마나 가렸나"
     * 이므로 식별자·상태·집계값·시각만 내린다.
     *
     * <p>리포트 집계 4종({@code faceDtctCnt}~{@code prcsEndDt})은 {@code null} 일 수 있다 —
     * 컬럼 신설(V184) 이전 회차이거나, 리포트 조회에 실패한 회차다(완료 자체는 성공했을 수 있다).
     *
     * @param procLogSn    회차 식별자(원장 PK)
     * @param procSttsCd   처리 상태 — REQUESTED / SUCCEEDED / FAILED
     * @param reqKndCd     요청 종류 — null=배치 비식별, REDEIDENT=검수완료 재비식별
     * @param reqDt        위탁 요청 일시(우리 시각)
     * @param resDt        처리 종결 일시(우리 시각, 성공·실패 공통)
     * @param faceDtctCnt  얼굴 검출 수(외부 리포트)
     * @param noPltDtctCnt 번호판 검출 수(외부 리포트)
     * @param frmeCnt      총 프레임 수(외부 리포트)
     * @param prcsBgngDt   외부 솔루션의 처리 시작 일시
     * @param prcsEndDt    외부 솔루션의 처리 종료 일시
     */
    public record DeidentHistoryDto(
            Long procLogSn,
            String procSttsCd,
            String reqKndCd,
            LocalDateTime reqDt,
            LocalDateTime resDt,
            Long faceDtctCnt,
            Long noPltDtctCnt,
            Long frmeCnt,
            LocalDateTime prcsBgngDt,
            LocalDateTime prcsEndDt
    ) {}

    public static VideoDetailResponse from(LsDataRaw e) {
        return from(e, null, null, 0L, Collections.emptyList(), null, Collections.emptyList(), null);
    }

    public static VideoDetailResponse from(LsDataRaw e, String cctvName, String localGov, Long frameCount) {
        return from(e, cctvName, localGov, frameCount, Collections.emptyList(), null, Collections.emptyList(), null);
    }

    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, null, Collections.emptyList(), null);
    }

    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, Collections.emptyList(), null);
    }

    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd,
            List<StageStatusDto> stages
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, stages, null);
    }

    /** DEV_FIX(H10) — 영상 실 fps 까지 포함한 빌드(마킹 화면 frameIndex 정합용). */
    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd,
            List<StageStatusDto> stages,
            Double fps
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, stages, fps,
                Collections.emptyList());
    }

    /** R14 — 비식별 이력까지 포함한 빌드. 승인 이력은 false 로 위임한다(하위호환). */
    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd,
            List<StageStatusDto> stages,
            Double fps,
            List<DeidentHistoryDto> deidentHistory
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, stages, fps,
                deidentHistory, false);
    }

    /** P2b — 승인 이력까지 포함한 빌드. 배치 실패 사유는 null 로 위임한다(하위호환). */
    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd,
            List<StageStatusDto> stages,
            Double fps,
            List<DeidentHistoryDto> deidentHistory,
            boolean everApproved
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, stages, fps,
                deidentHistory, everApproved, null);
    }

    /**
     * 배치 실패 사유까지 포함한 빌드. 수동 스킵 목록은 빈 배열로 위임한다(하위호환). [@design API-043]
     *
     * @param batchFailureReason 사용자 문구로 변환된 실패 사유. 실패가 아니면 {@code null}.
     *                           <b>내부 원문을 넘기지 말 것</b> — 변환은 {@code BatchFailureReasonPolicy} 담당.
     */
    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd,
            List<StageStatusDto> stages,
            Double fps,
            List<DeidentHistoryDto> deidentHistory,
            boolean everApproved,
            String batchFailureReason
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, stages, fps,
                deidentHistory, everApproved, batchFailureReason, Collections.emptyList());
    }

    /**
     * 수동 스킵 묶음 목록까지 포함한 전체 빌드 — 위 오버로드들은 전부 여기로 위임한다(하위호환). [@design API-043]
     *
     * @param skippedStages 검수자가 건너뛴 작업 묶음 코드(VLM/AUTOLABEL). 없으면 빈 리스트.
     *                      {@code null} 을 넘겨도 빈 배열로 정규화된다 — 응답 계약이 "빈 배열"이라
     *                      화면이 {@code null} 분기를 하지 않아도 되게 한다.
     */
    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd,
            List<StageStatusDto> stages,
            Double fps,
            List<DeidentHistoryDto> deidentHistory,
            boolean everApproved,
            String batchFailureReason,
            List<String> skippedStages
    ) {
        // 표시명 폴백(CCTV명 → CCTV ID → 영상 #{rawSn})은 목록 응답과 <같은 판정기>를 쓴다.
        String resolvedCctv = CctvDisplayNamePolicy.resolve(cctvName, e.getVmsCctvId(), e.getRawSn());
        String resolvedGov = (localGov != null && !localGov.isBlank()) ? localGov : e.getLclgvCd();
        Long resolvedFrame = (frameCount != null) ? frameCount : 0L;
        List<FramePreviewDto> resolvedPreviews = (framePreviews != null) ? framePreviews : Collections.emptyList();
        List<StageStatusDto> resolvedStages = (stages != null) ? stages : Collections.emptyList();
        List<DeidentHistoryDto> resolvedHistory =
                (deidentHistory != null) ? deidentHistory : Collections.emptyList();
        return new VideoDetailResponse(
                e.getRawSn(),
                resolvedCctv,
                e.getEvntTypeCd(),
                e.getEvntTypeCd(),
                resolvedGov,
                resolvedFrame,
                e.getDataSttsCd(),
                reviewSttsCd,
                e.getRawSn(),
                e.getVmsClipId(),
                e.getVmsCctvId(),
                e.getEvntTypeCd(),
                e.getLclgvCd(),
                e.getPrvcTypeCd(),
                e.getPrvcYn(),
                e.getDeIdntfYn(),
                e.getRawFilePathNm(),
                e.getShtDt(),
                e.getDurationSec(),
                e.getDataSttsCd(),
                e.getRegDt(),
                e.getMdfcnDt(),
                resolvedPreviews,
                resolvedStages,
                fps,
                e.isDerivative(),
                resolvedHistory,
                everApproved,
                batchFailureReason,
                (skippedStages != null) ? skippedStages : Collections.emptyList()
        );
    }
}
