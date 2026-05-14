package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * FE 정합 — 프레임 단위 라벨 커밋 1건 (Gitea 메타 + DB 메타 조합).
 *
 * <p>FE {@code Version} 인터페이스와 동일한 와이어 포맷:
 * {@code { commitSha, shortHash, authorName, message, committedAt, isCurrent }}
 *
 * <p>Gitea 응답이 비어 있거나 Gitea 호출 실패 시 DB({@code LsDataLblHstry})만으로 생성하여
 * 새로 등록된 영상(아직 commit 0건)도 200 + 빈 배열로 응답할 수 있게 한다.
 */
public record VersionItem(
        String commitSha,
        String shortHash,
        String authorName,
        String message,
        Instant committedAt,
        boolean isCurrent
) {

    /** 표시용 짧은 SHA (7자) — 빈/짧은 SHA 는 안전하게 잘림. */
    public static String shortOf(String sha) {
        if (sha == null) return "";
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    /** Gitea 응답 1건을 그대로 매핑 (인덱스 0 이면 현재 HEAD). */
    public static VersionItem fromGitea(CommitResponse c, boolean current) {
        String sha = c.sha() == null ? "" : c.sha();
        return new VersionItem(
                sha,
                shortOf(sha),
                c.author() == null ? "" : c.author(),
                c.message() == null ? "" : c.message(),
                c.date(),
                current
        );
    }

    /** Gitea 호출 실패 fallback — DB {@link LsDataLblHstry} 단독으로 생성. */
    public static VersionItem fromHistory(LsDataLblHstry h, boolean current) {
        String sha = h.getGiteaCmtHash() == null ? "" : h.getGiteaCmtHash();
        Instant ts = h.getRegisteredAt() == null
                ? null
                : h.getRegisteredAt().toInstant(ZoneOffset.UTC);
        return new VersionItem(
                sha,
                shortOf(sha),
                h.getRegisteredUserNo() == null ? "" : h.getRegisteredUserNo(),
                "",
                ts,
                current
        );
    }
}
