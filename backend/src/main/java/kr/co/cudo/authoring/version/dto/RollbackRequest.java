package kr.co.cudo.authoring.version.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 롤백 대상 프레임 식별 — 어떤 srcSn 의 라벨을 이 커밋 시점으로 되돌릴지.
 * 커밋 해시는 path 변수로 받음.
 */
public record RollbackRequest(
        @NotNull Long srcSn
) {
}
