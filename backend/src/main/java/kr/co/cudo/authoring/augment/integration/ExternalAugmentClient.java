package kr.co.cudo.authoring.augment.integration;

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
     * @param command 위탁 컨텍스트(멱등 키·비식별 입력 파일·콜백 URL 등)
     * @return 외부가 발급한 job_id 를 담은 결과. 외부 호출을 하지 않는 구현체는
     *         {@link AugmentSubmitResult#skipped()}.
     * @throws RuntimeException 위탁 실패(4xx/5xx/네트워크). 호출부가 사유를 DB 에 남긴다.
     */
    AugmentSubmitResult requestAugment(AugmentSubmitCommand command);
}
