package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.version.entity.LsLabelVersion;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * FE 정합 — 프레임 단위 라벨 버전 1건 (DB 스냅샷 기반).
 *
 * <p>FE {@code Version} 인터페이스 와이어 포맷 유지:
 * {@code { commitSha, shortHash, authorName, message, committedAt, isCurrent }}
 *
 * <p>Phase 5 (DB 스냅샷 전환) 이후 {@code commitSha} 는 외부 커밋 SHA 가 아니라
 * 라벨 스냅샷의 SHA-256(versionHash) 이다. FE 식별자/diff/rollback 키로 그대로 사용한다.
 */
public record VersionItem(
        String commitSha,
        String shortHash,
        String authorName,
        String message,
        Instant committedAt,
        boolean isCurrent
) {

    /** 표시용 짧은 해시 (7자) — 빈/짧은 해시는 안전하게 잘림. */
    public static String shortOf(String hash) {
        if (hash == null) return "";
        return hash.length() <= 7 ? hash : hash.substring(0, 7);
    }

    public static VersionItem fromLabelVersion(LsLabelVersion v, boolean current) {
        String hash = v.getVersionHash() == null ? "" : v.getVersionHash();
        Instant ts = v.getRegDt() == null
                ? null
                : v.getRegDt().toInstant(ZoneOffset.UTC);
        return new VersionItem(
                hash,
                shortOf(hash),
                v.getRegId() == null ? "" : v.getRegId(),
                v.getSaveReasonCd() == null ? "" : v.getSaveReasonCd(),
                ts,
                current
        );
    }
}
