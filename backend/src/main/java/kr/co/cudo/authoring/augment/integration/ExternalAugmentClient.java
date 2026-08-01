package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.augment.integration.dto.GenAiCancelResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobResultsResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobStatusResponse;
import reactor.core.publisher.Mono;

/**
 * 외부 SFR-07 증강(생성형 AI) 시스템 연동 클라이언트.
 *
 * <p>Phase 7-A1 에서 「생성형 AI API 연동명세서 v1.1」 계약으로 전면 정합했다. 핵심 변경:
 * <ul>
 *   <li><b>job_id 발급 주체 반전</b> — 우리는 {@code request_id}(멱등 키)만 발급하고,
 *       {@code job_id} 는 외부가 202 응답으로 발급한다. 그래서 반환 타입이 boolean 이 아니라
 *       {@link AugmentSubmitResult} 다(외부 job_id 를 돌려받아야 하므로).</li>
 *   <li><b>분할 위탁</b> — {@code input_files} 상한(100)을 넘으면 호출부가 청크로 나눠
 *       여러 번 호출한다. 커맨드의 {@code jobSeq}/{@code jobCount} 가 그 순서다.</li>
 * </ul>
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>{@code HttpExternalAugmentClient} — 기본({@code authoring.augment.external.mode=http}).
 *       실제 {@code POST /api/genai/jobs} 호출.</li>
 *   <li>{@link NoopExternalAugmentClient} — {@code mode=noop} 명시 시에만. 외부 미연동 운영용.</li>
 * </ul>
 *
 * <h3>★ 이 인터페이스는 순수 HTTP 어댑터다 (Phase 7-A2 구속)</h3>
 * <p>어떤 구현체도 <b>DB 를 쓰지 않고 파일을 만들지 않는다</b>. 응답 DTO 를 돌려주는 것이 전부다.
 * 조회 경로가 결과를 "적용" 하기 시작하면 웹훅 경로({@code GenAiCallbackService})와 <b>이중 적용</b>이
 * 되어 파생 산출물이 중복 생성된다 — 웹훅은 {@code OTSD_JOB_ID} UNIQUE + {@code FOR UPDATE} 락으로
 * 멱등을 지키고 있고, 조회 경로는 그 락 바깥이다. 결과 적용은 소비 계층의 책임이다.
 *
 * <h3>★ 진행 상태의 단일 진실원은 DB 다</h3>
 * <p>{@code LS_DATA_AUG_JOB.JOB_STTS_CD}(웹훅으로 갱신)가 진실원이고 {@link #fetchJobStatus}/
 * {@link #fetchJobResults} 는 <b>웹훅이 아직 안 왔을 때의 보조 신호</b>다. 조회가 {@code RUNNING} 인데
 * DB 가 {@code SUCCEEDED} 인 불일치는 <b>정상</b>이다(웹훅이 먼저 도착한 경우). 조회 결과로 DB 를
 * 덮어쓰지 말 것 — 종결 상태가 되돌려지면 롤업이 무한 대기에 빠진다.
 */
public interface ExternalAugmentClient {

    /**
     * 외부 시스템에 검수 결정을 통보. 실패 시 로그만 남기고 호출자 트랜잭션은 유지.
     *
     * <p>본 메서드는 Phase 7-A1 범위 밖이라 시그니처를 유지한다.
     *
     * @param dataAugSn   증강 행 식별자 (LS_DATA_AUG.DATA_AUG_SN)
     * @param decision    검수 결정 (ACCEPTED/REJECTED)
     * @param reasonOrNull 반려 사유 (ACCEPT 시 null)
     * @return 외부 시스템 ack 여부
     */
    boolean syncDecision(Long dataAugSn, String decision, String reasonOrNull);

    /**
     * 외부 시스템에 증강 작업을 위탁한다(job 1건 = 커맨드 1건).
     *
     * <h3>★ 반환이 {@link Mono} 다 — 스레드를 점유하지 않는다 (Phase C-3)</h3>
     * <p>사용자 확정 원칙 "외부연동은 모두 비동기" 의 <b>스레드 축</b>이다. 프로토콜은 원래부터
     * 비동기였으나(결과는 웹훅) <b>202 ACK 왕복 동안 스레드를 점유</b>했다({@code .block()}). 그 스레드는
     * {@code AugmentRequestBridge} 의 {@code batchAsyncExecutor}(core 2, CallerRuns)였고, 벤더가 느려지면
     * 배치 풀이 통째로 마르고 역압이 커밋 스레드까지 물고 늘어졌다.
     *
     * <p>구현체는 <b>구독 시점에</b> 호출을 개시해야 한다(반환 즉시 부작용 금지 — 재시도/취소 의미론
     * 보존). 호출부는 {@code publishOn(augmentSubmitScheduler)} 로 완료 신호를 전용 풀에 고정한다.
     *
     * @param command 위탁 컨텍스트(멱등 키·비식별 입력 파일·콜백 URL 등)
     * @return 외부가 발급한 job_id 를 담은 결과의 {@link Mono}. 외부 호출을 하지 않는 구현체는
     *         {@link AugmentSubmitResult#skipped()}. 위탁 실패(4xx/5xx/네트워크)는 {@code onError} 로
     *         전달되며 호출부가 사유를 DB 에 남긴다.
     */
    Mono<AugmentSubmitResult> requestAugment(AugmentSubmitCommand command);

    /**
     * 외부 작업 상태를 조회한다 — 명세서 §4.4 {@code GET /api/genai/jobs/{job_id}} (INT-020).
     *
     * <p>진행 상태의 진실원은 DB 이며 본 조회는 보조 신호다(클래스 Javadoc 참조). 응답은 계약 검증을
     * 통과한 것만 돌려준다 — 미지 {@code status}, job_id echo 불일치, 범위 밖 {@code progress} 는
     * fail-closed 로 실패한다.
     *
     * @param externalJobId 외부가 발급한 job_id. null/공백/형식 위반이면 <b>외부 호출 없이</b> 즉시 실패한다.
     * @return 상태 응답. 외부 미연동(noop)이면
     *         {@link AugmentQueryResult.SkipReason#EXTERNAL_DISABLED} 스킵 결과(가짜 상태 조립 금지)
     */
    Mono<AugmentQueryResult<GenAiJobStatusResponse>> fetchJobStatus(String externalJobId);

    /**
     * 외부 작업 결과를 조회한다 — 명세서 §4.5 {@code GET /api/genai/jobs/{job_id}/results} (INT-030).
     *
     * <p>계약상 {@code SUCCEEDED} 에서만 200 이고 그 외는 409 {@code STATE_CONFLICT} 다(비재시도).
     * {@code SUCCEEDED} 인데 {@code results} 가 비면 계약 위반으로 실패시킨다 — 웹훅 경로와 동일 규칙.
     *
     * <p><b>⚠ 반환된 {@code output_file_path} 는 외부가 준 NAS 절대경로다.</b> 파일시스템 API 에
     * 넘기기 전에 {@code VideoArtifactRootResolver#verifyExternalReadablePath} 로 정규화·허용 루트
     * 검증을 반드시 거칠 것(CWE-22). 이 검증을 빠뜨리면 웹훅 경로가 이미 막아 둔 경로 순회 벡터가
     * 조회 경로로 그대로 재현된다.
     *
     * <p><b>이 경고는 주석이 아니라 테스트로 강제된다</b> —
     * {@code ExternalAugmentClientContractGuardTest} 가 (1) 이 연동 계층의 DB/파일시스템 접촉과
     * (2) {@code outputFilePath} 를 파일시스템 API 와 함께 다루면서 검증을 거치지 않는 프로덕션 파일을
     * 정적 스캔으로 차단한다. 주석만으로는 다음 개발자를 못 막는다는 것이 이 리포지토리의 실사고
     * 교훈이다(2026-07-30 라벨링 캔버스 원본 프레임 서빙).
     *
     * @param externalJobId 외부가 발급한 job_id. null/공백/형식 위반이면 <b>외부 호출 없이</b> 즉시 실패한다.
     * @return 결과 응답. 외부 미연동(noop)이면 {@link AugmentQueryResult.SkipReason#EXTERNAL_DISABLED}
     */
    Mono<AugmentQueryResult<GenAiJobResultsResponse>> fetchJobResults(String externalJobId);

    /**
     * 외부 작업을 취소한다 — 명세서 §4.6 {@code POST /api/genai/jobs/{job_id}/cancel} (INT-031).
     *
     * <p>{@code RECEIVED}·{@code RUNNING} 에서만 성립하며 종결 상태는 409 {@code STATE_CONFLICT} 다.
     * 계약상 취소는 <b>웹훅을 발사하지 않으므로</b> 이 동기 응답이 유일한 통보다. 취소 성공을 DB 에
     * 반영하는 것은 호출부의 책임이다(이 클라이언트는 DB 를 쓰지 않는다).
     *
     * @param command 취소 컨텍스트. {@code requested_by} 는 {@link AugmentCancelCommand#actor()}
     *                (인증 주체)에서만 파생된다.
     * @return 취소 응답. 로컬 DB 가 이미 종결이면
     *         {@link AugmentQueryResult.SkipReason#LOCAL_TERMINAL} 로 <b>외부 호출 없이</b> 스킵하고,
     *         외부 미연동(noop)이면 {@link AugmentQueryResult.SkipReason#EXTERNAL_DISABLED}
     */
    Mono<AugmentQueryResult<GenAiCancelResponse>> cancelJob(AugmentCancelCommand command);
}
