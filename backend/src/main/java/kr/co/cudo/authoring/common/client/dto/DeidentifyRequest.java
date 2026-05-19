package kr.co.cudo.authoring.common.client.dto;

/**
 * Deidentify SPI 위탁 요청 — Phase 3 보강.
 *
 * <p>{@code idempotencyKey} 는 본 도구가 발급. null/blank 인 경우
 * {@link kr.co.cudo.authoring.common.client.DeidentifyClient} 가 UUID 로 자동 발급한다.
 * 동일 키로 재호출 시 외부 시스템은 멱등 응답을 반환해야 한다.
 */
public record DeidentifyRequest(String sourcePath, String targetPath, String idempotencyKey) {

    /** 호환 생성자 — idempotencyKey 미지정. 기존 호출자 시그니처 보존. */
    public DeidentifyRequest(String sourcePath, String targetPath) {
        this(sourcePath, targetPath, null);
    }
}
