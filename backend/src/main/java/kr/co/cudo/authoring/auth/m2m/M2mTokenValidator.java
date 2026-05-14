package kr.co.cudo.authoring.auth.m2m;

/**
 * M2M 토큰 검증기. Phase 4 부터 경로(scope) 별 토큰 분리 검증을 지원한다.
 * <p>scope 별 토큰을 분리해 운영해야 하므로 단일 {@code isValid(token)} 만으로는
 * CONTROL 토큰으로 LEARNING_DATA 경로 우회가 가능했던 위험을 차단한다.
 */
public interface M2mTokenValidator {

    /**
     * M2M 토큰 스코프 (경로별 토큰 분리).
     * <ul>
     *   <li>{@code CONTROL} — 관제서버 → 저작도구 영상 ingest (`/v1/integration/control/**`)</li>
     *   <li>{@code LEARNING_DATA} — 외부 학습데이터 API 소비 (`/v1/export-api/**`)</li>
     * </ul>
     */
    enum Scope {
        CONTROL,
        LEARNING_DATA
    }

    /**
     * 주어진 토큰이 해당 scope 의 유효 토큰인지 검증.
     * 다른 scope 의 토큰으로 우회 호출하는 경우 false 반환.
     */
    boolean isValid(String token, Scope scope);
}
