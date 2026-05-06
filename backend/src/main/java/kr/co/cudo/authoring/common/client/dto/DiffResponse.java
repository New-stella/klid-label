package kr.co.cudo.authoring.common.client.dto;

import java.util.List;

/**
 * Gitea compare API (GET /api/v1/repos/{owner}/{repo}/compare/{base}...{head}) 응답.
 *  - files : 변경된 파일 목록 (라벨 단위로 묶이며 path 는 GiteaPathPolicy 가 결정)
 */
public record DiffResponse(List<DiffFile> files) {
}
