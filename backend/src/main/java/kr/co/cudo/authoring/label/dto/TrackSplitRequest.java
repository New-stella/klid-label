package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * R5 — 트랙 split(분할) 요청. {@code atFrameNo} 이후(포함) 프레임의 트랙 라벨을 새 트랙 ID 로 분리한다.
 *
 * <p>입력 검증(CWE-20): {@code atFrameNo} 는 필수 + 0 이상(음수 프레임 거부). 트랙 존재성·경계(범위 밖)는
 * 서비스에서 각각 404 / movedCount=0 으로 처리한다.
 *
 * @param atFrameNo 분할 기준 프레임 번호 — 이 값 이상 프레임이 새 트랙으로 이동, 이전은 원 트랙 유지
 */
public record TrackSplitRequest(
        @NotNull(message = "atFrameNo 는 필수입니다.")
        @Min(value = 0, message = "atFrameNo 는 0 이상이어야 합니다.")
        Integer atFrameNo
) {
}
