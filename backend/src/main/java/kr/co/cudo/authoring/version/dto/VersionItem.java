package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.version.entity.LsLabelVersion;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * FE 정합 — 프레임 단위 라벨 버전 1건 (DB 스냅샷 기반).
 *
 * <p>FE {@code Version} 인터페이스 와이어 포맷 유지:
 * {@code { commitSha, shortHash, authorName, authorNo, message, committedAt, isCurrent }}
 *
 * <p>Phase 5 (DB 스냅샷 전환) 이후 {@code commitSha} 는 외부 커밋 SHA 가 아니라
 * 라벨 스냅샷의 SHA-256(versionHash) 이다. FE 식별자/diff/rollback 키로 그대로 사용한다.
 *
 * <p><b>★ 계약 변경 (사번 노출 정정)</b> — {@code authorName} 은 원래 필드명과 달리 {@code REG_ID}
 * (사번)를 담고 있어 화면에 "2001" 같은 숫자가 그대로 찍혔다. 이제 {@code authorName} 은 실제 표시명
 * ({@code LS_ACNT_USER.USER_NM})이고, 사번은 <b>{@code authorNo} 로 분리</b>해 함께 내려준다.
 * 이름 해석에 실패하면(비숫자 사번·마스터 미존재) {@code authorName} 은 {@code null} 이므로 화면은
 * {@code authorNo} 로 폴백해야 한다(빈칸 금지).
 */
public record VersionItem(
        String commitSha,
        String shortHash,
        String authorName,
        String authorNo,
        String message,
        Instant committedAt,
        boolean isCurrent
) {

    /** 표시용 짧은 해시 (7자) — 빈/짧은 해시는 안전하게 잘림. */
    public static String shortOf(String hash) {
        if (hash == null) return "";
        return hash.length() <= 7 ? hash : hash.substring(0, 7);
    }

    /**
     * 버전 행 + <b>해석된 작성자 표시명</b> → 응답 매핑.
     *
     * <p>{@code authorName} 은 호출부(서비스)가 목록 단위 <b>배치 조회 1회</b>로 해석해 넘긴다(N+1 금지).
     * 해석 실패는 {@code null} 이며 {@code authorNo}(사번)는 언제나 원값 그대로 유지된다.
     */
    public static VersionItem fromLabelVersion(LsLabelVersion v, boolean current, String authorName) {
        String hash = v.getVersionHash() == null ? "" : v.getVersionHash();
        Instant ts = v.getRegDt() == null
                ? null
                : v.getRegDt().toInstant(ZoneOffset.UTC);
        return new VersionItem(
                hash,
                shortOf(hash),
                authorName,
                v.getRegId(),
                v.getSaveReasonCd() == null ? "" : v.getSaveReasonCd(),
                ts,
                current
        );
    }
}
