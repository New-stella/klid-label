package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * YOLO 객체 트랙 추론 요청 (온디맨드 프록시).
 *
 * <p>주의: {@code common/client/dto/YoloTrackRequest} 와 이름이 같지만 다른 패키지다.
 * 본 record 는 컨트롤러 진입용(FE 계약), client 쪽은 ai-server 호출용이다.
 *
 * <ul>
 *   <li>srcSn      : 시작(첫) 프레임 — frameIndex=0, ai-server 트래커 상태 리셋</li>
 *   <li>nextSrcSns : 후속 프레임 순서(정렬됨). frameIndex 1..N. CWE-770 상한 50</li>
 * </ul>
 * 전체 추적 시퀀스 = [srcSn] + nextSrcSns 순서.
 */
public record YoloTrackRequest(
        @NotNull Long srcSn,
        @NotEmpty @Size(max = 50) List<Long> nextSrcSns
) {
}
