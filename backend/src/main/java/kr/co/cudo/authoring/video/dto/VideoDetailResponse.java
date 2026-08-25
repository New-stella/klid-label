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
        List<String> skippedStages,
        /*
         * 건너뛰기가 <b>해제된</b> 작업 묶음 목록 — VLM(시계열) / AUTOLABEL. [@design API-043] [@design ADR-050]
         *
         * ★ skippedStages 의 <b>뒷면</b>이다. 사람이 직접 푼 해제와 <b>재수행이 자동으로 푼 해제</b>가
         * 모두 담기며, 둘은 사유 본문으로만 갈리고 상태 축에서는 같은 「해제됨」이다.
         *
         * ★ 이 필드가 없으면 <b>한 번 재수행한 영상을 화면에서 다시 재수행할 수 없다</b>: 재수행이 건너뜀
         * 표식을 스스로 풀면서 해제 표식을 남기므로 그 묶음은 skippedStages 에서 빠진다. 화면은 두 목록의
         * <b>합집합</b>으로 재수행 버튼 노출을 정한다.
         *
         * 두 목록 모두 화면 세션과 무관한 <b>영구 상태</b>이며, 해당 묶음이 없으면 빈 배열이다(null 아님).
         * 순서는 묶음 선언 순서(VLM → AUTOLABEL) 고정.
         *
         * 기존 from(...) 오버로드로 만든 응답은 빈 배열이므로 이 필드가 추가돼도 기존 소비자는
         * 영향받지 않는다(추가만 — 하위호환). skippedStages 의 시맨틱은 <b>바뀌지 않는다</b>(지금 건너뛴 상태).
         */
        List<String> clearedStages,
        /*
         * <b>지금 실패한 상태인</b> 작업 묶음 목록 — VLM(시계열) / AUTOLABEL. [@design API-043] [@design ADR-050]
         *
         * ★ 왜 필요한가: 시계열 위탁은 논블로킹이라 <b>실패해도 예외가 위로 올라가지 않는다</b> — 배치
         * 상태(status)는 완료로 남고 stages 에도 실패 표시가 서지 않는다. 그래서 위탁이 확정 실패한
         * 영상에서 화면은 실패를 알 방법이 없었고, 건너뛰기·재수행 버튼이 <b>어디에도 뜨지 않았다</b>
         * (서버는 허용하는데 사람이 누를 자리가 없는 상태).
         *
         * ★ 판정은 건너뛰기 허용 여부를 정하는 서버 판정과 <b>같은 것</b>이다 — 그래야 화면에 뜬 버튼이
         * 눌렀을 때 412 로 튕기지 않는다.
         *
         * 값 집합은 skippedStages·clearedStages 와 같고(VLM/AUTOLABEL), 실패가 없으면 <b>빈 배열</b>
         * (null 아님)이다. 순서는 묶음 선언 순서(VLM → AUTOLABEL) 고정.
         *
         * 기존 from(...) 오버로드로 만든 응답은 빈 배열이므로 이 필드가 추가돼도 기존 소비자는
         * 영향받지 않는다(추가만 — 하위호환).
         */
        List<String> failedStages,
        /*
         * 이 영상의 <b>검증 이벤트 유형 코드</b> — 관제 인입 원장에서 수신한 값. 미수신이면 null.
         * [@design API-043] [@design ERD-033]
         *
         * ★ 관제 이벤트유형 코드(evntTypeCd, 예 EV01000101)와 <b>축이 다른 값</b>이다 — 서로
         * 대체하지 않으며 한쪽에서 다른 쪽을 유도하지 않는다(그 유도표가 곧 이 설계가 피하려던
         * 자체 매핑표다).
         *
         * 표기는 조달 판정기와 <b>같은 정규화</b>({@code LsDataIngest.normalizeVrfcEvntType})를 거친
         * 값이다 — 아래 질문 목록을 찾을 때 쓴 키와 응답에 실린 코드가 어긋나면 화면이 "이 유형의
         * 질문"이라고 보여준 것의 근거가 사라진다.
         */
        String vrfcEvntTypeCd,
        /*
         * 위 유형에 등록된 <b>질문 목록</b> — 정렬순서 오름차순, 첫 번째가 그 유형의 기본 질문이다.
         * [@design API-043] [@design ERD-033]
         *
         * ★ 왜 이 응답에 싣는가: 마킹 화면이 작업자에게 질문을 보여주고 고른 값을 마킹 등록 요청에
         * 실어야 하는데, 질문 카탈로그를 관리하는 조회 경로는 <b>검수자 전용</b>이라 마킹 작업자에게
         * 403 이다. 마킹 화면이 이미 이 응답을 소비하므로 경로를 새로 만들지 않고 여기에 싣는다.
         *
         * ★ 그 영상에 해당하는 <b>유형 하나</b>의 질문일 뿐 관리용 카탈로그 전체가 아니다.
         *
         * 유형이 없거나(인입 행이 없는 파생영상 등) 등록된 질문이 0건이면 <b>빈 배열</b>이다
         * (null 아님) — <b>오류가 아니다</b>. 카탈로그는 허용목록이 아니므로 목록에 없는 유형의
         * 영상도 정상 조회되고 질문 칸만 빈다.
         *
         * 기존 from(...) 오버로드로 만든 응답은 null + 빈 배열이므로 이 필드들이 추가돼도 기존
         * 소비자는 영향받지 않는다(추가만 — 하위호환).
         */
        List<VrfcEvntQuestionDto> vrfcEvntQuestions
) {
    /**
     * 검증 이벤트 유형 질문 1건 — 화면이 고를 항목. [@design API-043] [@design ERD-033]
     *
     * <p><b>식별자와 본문만</b> 싣는다: 화면은 본문을 보여주고 고른 식별자를 마킹 등록 요청에 실으면
     * 되며, 정렬순서는 배열 순서가 이미 나타내므로 별도 필드로 내리면 화면이 서버 정렬을 다시
     * 유도할 여지를 만든다(「첫 번째 질문」의 해석은 서버 판정기 한 곳에만 둔다).
     *
     * @param vrfcEvntQstnSn 검증이벤트질문일련번호 — 마킹 등록에 실어 고른 질문을 가리키는 값
     * @param qstnCn         질문 문구 — 작업자에게 보여줄 본문
     */
    public record VrfcEvntQuestionDto(Long vrfcEvntQstnSn, String qstnCn) {}

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
     * 수동 스킵 묶음 목록까지 포함한 빌드 — 해제 목록은 빈 배열로 위임한다(하위호환). [@design API-043]
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
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, stages, fps,
                deidentHistory, everApproved, batchFailureReason, skippedStages, Collections.emptyList());
    }

    /**
     * 해제된 묶음 목록까지 포함한 빌드 — 실패 묶음 목록은 빈 배열로 위임한다(하위호환).
     * [@design API-043] [@design ADR-050]
     *
     * @param clearedStages 건너뛰기가 해제된 작업 묶음 코드(VLM/AUTOLABEL). 없으면 빈 리스트.
     *                      {@code null} 을 넘겨도 빈 배열로 정규화된다.
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
            List<String> skippedStages,
            List<String> clearedStages
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, stages, fps,
                deidentHistory, everApproved, batchFailureReason, skippedStages, clearedStages,
                Collections.emptyList());
    }

    /**
     * 실패 묶음 목록까지 포함한 빌드 — 검증 이벤트 질문 축은 비운 채 위임한다(하위호환).
     * [@design API-043] [@design ADR-050]
     *
     * @param failedStages 지금 실패한 상태인 작업 묶음 코드(VLM/AUTOLABEL). 없으면 빈 리스트.
     *                     {@code null} 을 넘겨도 빈 배열로 정규화된다.
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
            List<String> skippedStages,
            List<String> clearedStages,
            List<String> failedStages
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, stages, fps,
                deidentHistory, everApproved, batchFailureReason, skippedStages, clearedStages,
                failedStages, null, Collections.emptyList());
    }

    /**
     * 검증 이벤트 유형·질문 목록까지 포함한 <b>전체</b> 빌드 — 위 오버로드들은 전부 여기로
     * 위임한다(하위호환). [@design API-043] [@design ERD-033]
     *
     * @param vrfcEvntTypeCd    그 영상의 검증 이벤트 유형 코드. 미수신이면 {@code null}.
     *                          <b>정규화된 값</b>을 넘긴다 — 질문 목록을 찾은 키와 같은 값이어야 한다.
     * @param vrfcEvntQuestions 그 유형에 등록된 질문 목록(정렬순서 오름차순). 유형이 없거나 질문이
     *                          0건이면 빈 리스트. {@code null} 을 넘겨도 빈 배열로 정규화된다 —
     *                          응답 계약이 "빈 배열"이라 화면이 {@code null} 분기를 하지 않아도 되게 한다.
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
            List<String> skippedStages,
            List<String> clearedStages,
            List<String> failedStages,
            String vrfcEvntTypeCd,
            List<VrfcEvntQuestionDto> vrfcEvntQuestions
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
                (skippedStages != null) ? skippedStages : Collections.emptyList(),
                (clearedStages != null) ? clearedStages : Collections.emptyList(),
                (failedStages != null) ? failedStages : Collections.emptyList(),
                vrfcEvntTypeCd,
                (vrfcEvntQuestions != null) ? vrfcEvntQuestions : Collections.emptyList()
        );
    }
}
