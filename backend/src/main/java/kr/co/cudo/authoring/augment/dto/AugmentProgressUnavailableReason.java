package kr.co.cudo.authoring.augment.dto;

/**
 * 진행률({@code progress})을 <b>산출하지 못한 사유</b> — {@code GET /v1/augments/{id}/progress}.
 *
 * <h3>왜 사유를 구분해야 하는가 (S6)</h3>
 * <p>외부 조회가 실패하는 경로는 성격이 전혀 다른 셋인데, 구분하지 않으면 두 가지 사고가 동시에 난다:
 * <ul>
 *   <li><b>실제 벤더 장애가 "미연동" 으로 위장</b>된다 — 서킷 open({@code CallNotPermittedException})·
 *       타임아웃을 {@code EXTERNAL_DISABLED} 와 같게 취급하면 운영이 장애를 인지하지 못한다.</li>
 *   <li><b>정상 상태가 "조회 실패" 로 보인다</b> — 외부 연동이 없는 형상에서는 {@link #NOOP} 이
 *       나온다. 이걸 오류로 렌더링하면 사용자가 영원히 스피너·에러를 본다.</li>
 * </ul>
 *
 * <h3>FE 표시 지침 (Phase 5 인계)</h3>
 * <table border="1">
 *   <caption>사유별 화면 처리</caption>
 *   <tr><th>값</th><th>의미</th><th>표시</th></tr>
 *   <tr><td>{@link #NOOP}</td><td>외부 연동 자체가 꺼져 있음(설정)</td>
 *       <td>진행률 바를 <b>숨기고</b> "외부 증강 시스템 미연동" 안내. 오류 아님. 폴링 주기 완화.</td></tr>
 *   <tr><td>{@link #TRANSIENT_ERROR}</td><td>외부 호출 실패(서킷 open·타임아웃·5xx·계약 위반)</td>
 *       <td>진행률 자리에 "일시적으로 진행률을 가져오지 못했습니다" + 재시도 안내. 작업은 계속 진행 중.</td></tr>
 *   <tr><td>{@link #AWAITING_ACK}</td><td>외부 작업 ID 미수신(202 ACK 유실 또는 접수 직후)</td>
 *       <td>"접수 확인 중" 표시. <b>오류로 표시하지 말 것</b> — 정상 진행 중일 수 있다.</td></tr>
 *   <tr><td>{@link #QUERY_LIMIT_EXCEEDED}</td><td><b>우리 쪽</b> 자체 상한(청크 수·요청 시간 예산)</td>
 *       <td>"진행률을 표시할 수 없습니다(작업량이 많습니다)". <b>오류가 아니다</b> — 벤더는 정상
 *           처리 중이고 작업 자체도 정상 진행된다. 폴링 주기를 늦춘다.</td></tr>
 * </table>
 *
 * <p>이 값이 {@code null} 이면 {@code progress} 는 신뢰할 수 있는 값이다.
 */
public enum AugmentProgressUnavailableReason {

    /**
     * 외부 연동 비활성 — {@code AugmentQueryResult.SkipReason#EXTERNAL_DISABLED} 의 표시값.
     *
     * <p>⚠ <b>이 값을 만드는 구현체는 2026-09-03 이후 없다</b> — 그것을 만들던 미연동 모드 토글과
     * no-op 클라이언트가 폐기됐기 때문이다. <b>값과 매핑은 그대로 둔다</b>: 폐기한 것은 「나갈지
     * 말지를 고르는 환경설정 축」이지 이 표시 축이 아니고, 이미 내려간 응답을 읽는 소비자가 있다.
     */
    NOOP,

    /**
     * 외부 호출 실패 — 서킷 open · 타임아웃 · 5xx · 응답 계약 위반.
     * <b>진짜 장애</b>이므로 서버는 WARN 로그로 실측 근거를 남긴다.
     */
    TRANSIENT_ERROR,

    /**
     * 비종결 job 중 {@code OTSD_JOB_ID} 가 없는 것이 있음(202 ACK 유실 · 위탁 직후).
     *
     * <p>이 경우 <b>외부 호출을 개시하지 않는다</b> —
     * {@code HttpExternalAugmentClient#requireValidJobId} 는 null 에 예외를 던지므로(skip 이 아니다)
     * 클라이언트에 맡기면 정상 진행 중인 작업이 화면에서 에러로 보인다.
     */
    AWAITING_ACK,

    /**
     * <b>우리 쪽 자체 상한</b>으로 외부 조회를 다 하지 못했다 — 비종결 청크 수가
     * {@code AugmentProgressService.MAX_STATUS_QUERIES} 를 넘었거나, 요청 1회의 시간 예산
     * ({@code AugmentRequestBudget})이 소진됐다.
     *
     * <h3>왜 {@link #TRANSIENT_ERROR} 와 분리하는가 (DEV_FIX HIGH-2)</h3>
     * <p>이건 <b>벤더 장애가 아니다</b>. 초대형 영상(청크 11개 이상)에서는 폴링할 때마다 항상
     * 발생하는 <b>정상 상황</b>인데, 이를 "일시적 오류" 로 표기하면 운영 모니터링에서 실제 서킷 open
     * 과 <b>같은 알람으로 섞인다</b>. 영구적일 수 있는 조건을 "일시적" 이라 말하지 않는다.
     *
     * <p>진행률만 표시되지 않을 뿐 <b>작업은 정상 진행되고 결과 회수도 계속 시도된다</b>(상한 초과
     * 시에도 회수 블록에 도달한다 — 구 구현은 즉시 return 해 회수가 영구히 동작하지 않았다).
     * 조회 <b>창은 폴링마다 회전</b>하므로 상한을 넘는 청크도 유한한 폴링 안에 조회된다
     * ({@code AugmentStatusWindowRotator} — DEV_FIX 2차 MED-1).
     */
    QUERY_LIMIT_EXCEEDED
}
