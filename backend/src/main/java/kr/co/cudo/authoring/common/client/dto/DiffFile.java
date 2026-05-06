package kr.co.cudo.authoring.common.client.dto;

/**
 * Gitea diff 응답 — 변경된 파일 단위.
 *  - path      : 파일 경로
 *  - change    : 변경 종류 ("added" / "modified" / "removed" / "renamed")
 *  - additions : 추가된 라인 수
 *  - deletions : 삭제된 라인 수
 *  - patch     : unified diff (선택 — null 가능)
 */
public record DiffFile(String path, String change, int additions, int deletions, String patch) {
}
