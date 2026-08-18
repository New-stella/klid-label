package kr.co.cudo.authoring.label.dto;

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
 *   <li>nextSrcSns : 후속 프레임 순서(정렬됨). frameIndex 1..N. CWE-770 상한 50.
 *                    <b>빈 목록은 «시작 프레임만 처리» 로 정상 요청이다</b> — 아래 참조</li>
 * </ul>
 * 전체 추적 시퀀스 = [srcSn] + nextSrcSns 순서.
 */
public record YoloTrackRequest(
        @NotNull Long srcSn,
        /*
         * ★ 빈 목록을 허용한다 — 서버가 스스로 발행한 이어 보내기 값이 이 규격을 만족해야 하기 때문이다.
         *
         * 구 규칙(@NotEmpty, 폐기): 예산이 다했을 때 서버가 돌려주는 이어 보내기 값은 «아직 처리하지
         * 않은 첫 프레임 + 그 뒤 나머지» 로 갈린다. 남은 프레임이 정확히 하나면 «나머지» 가 빈 목록이
         * 되는데, 화면이 그 값을 그대로 되실으면 이 검증이 400 으로 거부했다. 그 경로에는 부분 결과
         * 보존이 없어 이미 계산된 마지막 프레임 검출이 통째로 버려진다 — 서버가 «자기가 받지 못할
         * 값» 을 발행하고 있었던 것이다. 회귀 가드: TrackResumeRequestContractTest.
         *
         * 풀어도 잃는 방어가 없다: 자원 상한은 @Size(max = 50) 가, 접근 통제는 프레임별 인가 검사가
         * 담당한다. @NotEmpty 는 «추적인데 후속 프레임이 없다» 는 의미 안내였을 뿐이고, 빈 목록은
         * 시퀀스가 [srcSn] 하나인 정상 요청으로 그대로 성립한다(추론 1회 — 가능한 가장 가벼운 요청).
         * null 은 여전히 거부한다(@NotNull) — 서비스가 목록을 순회하므로 계약을 흐리지 않는다.
         */
        @NotNull @Size(max = 50) List<Long> nextSrcSns
) {
}
