package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;

import java.util.Map;

/**
 * 배치 실패 사유를 <b>사용자에게 보여줄 문구</b>로 변환하는 단일 판정 지점. [@design API-043]
 *
 * <h3>왜 원문을 그대로 내보내지 않는가 (CWE-209)</h3>
 * <p>{@code LS_BATCH_PROC_LOG} 의 실패 축은 <b>내부 진단용</b>이다 — {@code ERR_CD} 는 예외 클래스
 * 단순명({@code CustomException}·{@code DataIntegrityViolationException} 등)이고 {@code ERR_MSG_CN}
 * 에는 DB 제약명·SQL 원문·파일 경로가 그대로 들어간다(dev 실측:
 * {@code could not execute statement [ERROR: duplicate key value violates unique constraint
 * "uk_ls_data_src_raw_frame"...}). 이 값을 API 응답에 실으면 스키마·내부 경로가 외부로 새고,
 * 그 자체가 결함이다. 따라서 응답에는 <b>여기서 만든 상수 문구만</b> 나간다.
 *
 * <h3>판정 규칙</h3>
 * <ol>
 *   <li>처리상태가 {@code FAILED} 가 아니면 {@code null}(실패가 아니면 사유 없음).</li>
 *   <li>단계({@code PROC_STEP_CD})별 기본 문구를 고른다. <b>단계를 특정할 수 없어도</b>
 *       ({@code PROC_STEP_CD='FAILED'} 등 표시 단계가 아닌 값 · {@code null}) 일반 문구로 폴백한다 —
 *       dev 실측상 실패 3건 중 1건이 이 형태라, 단계가 있을 때만 사유를 주면 그 영상은 아무것도 못 본다.</li>
 *   <li>알려진 원인 유형({@code ERR_CD})이면 원인 문구를 덧붙인다. <b>모르는 유형은 덧붙이지 않는다</b>
 *       (원문 노출 금지 — 폴백이 곧 기본 동작이다).</li>
 * </ol>
 *
 * <p>DB 접근이 없는 순수 정적 판정이라 단위 테스트가 가능하다. 호출처마다 복제하지 말 것 —
 * 문구가 갈리면 화면·감사·운영 안내가 서로 다른 말을 하게 된다.
 */
public final class BatchFailureReasonPolicy {

    /** 실패로 판정하는 유일한 처리상태 값 — {@link LsBatchProcLog#fail(Throwable)} 이 찍는 값. */
    static final String STTS_FAILED = "FAILED";

    /** 단계 미상·미등록 단계의 폴백 문구. 내부 원문을 대신하는 <b>최종 안전망</b>이다. */
    static final String GENERIC = "배치 처리 중 오류가 발생했습니다.";

    /** 단계별 기본 문구 — 사용자가 "어디서 멈췄는지"를 알 수 있는 최소한의 정보만 담는다. */
    private static final Map<String, String> STAGE_MESSAGES = Map.of(
            BatchStage.DEIDENTIFY.name(), "영상 비식별 처리에 실패했습니다.",
            BatchStage.MARKING.name(), "마킹 정보를 불러오지 못했습니다.",
            BatchStage.VLM.name(), "AI 시계열 분석 요청에 실패했습니다.",
            BatchStage.FRAME_EXTRACT.name(), "영상에서 프레임을 추출하지 못했습니다.",
            BatchStage.YOLO.name(), "AI 추론 서버 호출에 실패했습니다.",
            BatchStage.SAM2.name(), "AI 분할 추론에 실패했습니다.",
            BatchStage.INTERPOLATE.name(), "트랙 보간 처리에 실패했습니다."
    );

    /**
     * 알려진 원인 유형({@code ERR_CD} = 예외 클래스 단순명) → 사용자 문구.
     *
     * <p>여기 없는 유형은 <b>일부러 매핑하지 않는다</b> — 목록을 넓히려고 원문을 흘리면 이 정책의
     * 존재 이유가 사라진다. {@code CustomException} 처럼 메시지가 유용해 보이는 것도 제외한다
     * (그 메시지에 식별자·경로가 실릴 수 있다).
     */
    private static final Map<String, String> CAUSE_MESSAGES = Map.ofEntries(
            Map.entry("DataIntegrityViolationException", "저장 중 데이터 제약 조건 위반이 발생했습니다."),
            Map.entry("ConstraintViolationException", "저장 중 데이터 제약 조건 위반이 발생했습니다."),
            Map.entry("JpaSystemException", "저장 중 데이터 제약 조건 위반이 발생했습니다."),
            Map.entry("WebClientRequestException", "외부 서버 연결에 실패했습니다."),
            Map.entry("WebClientResponseException", "외부 서버가 오류를 반환했습니다."),
            Map.entry("ConnectException", "외부 서버 연결에 실패했습니다."),
            Map.entry("TimeoutException", "처리 시간이 초과되었습니다."),
            Map.entry("ReadTimeoutException", "처리 시간이 초과되었습니다."),
            Map.entry("CallNotPermittedException", "외부 서버 장애로 호출이 차단된 상태입니다."),
            Map.entry("IOException", "파일을 읽거나 쓰는 중 오류가 발생했습니다."),
            Map.entry("UncheckedIOException", "파일을 읽거나 쓰는 중 오류가 발생했습니다."),
            Map.entry("FileNotFoundException", "필요한 파일을 찾을 수 없습니다."),
            Map.entry("NoSuchFileException", "필요한 파일을 찾을 수 없습니다.")
    );

    private BatchFailureReasonPolicy() {
    }

    /**
     * 최신 배치 로그 1행의 실패 축을 사용자 문구로 변환한다.
     *
     * @param procStepCd 최신 로그의 {@code PROC_STEP_CD}(단계 미상일 수 있음, {@code null} 허용)
     * @param procSttsCd 최신 로그의 {@code PROC_STTS_CD}
     * @param errCd      최신 로그의 {@code ERR_CD}(예외 클래스 단순명, {@code null} 허용)
     * @return 사용자에게 보여줄 실패 사유. 실패가 아니면 {@code null}. <b>내부 원문은 절대 포함되지 않는다.</b>
     */
    public static String describe(String procStepCd, String procSttsCd, String errCd) {
        if (!STTS_FAILED.equals(procSttsCd)) {
            return null;
        }
        String stageMessage = procStepCd == null
                ? GENERIC
                : STAGE_MESSAGES.getOrDefault(procStepCd, GENERIC);
        String causeMessage = errCd == null ? null : CAUSE_MESSAGES.get(errCd);
        return causeMessage == null ? stageMessage : stageMessage + " " + causeMessage;
    }
}
