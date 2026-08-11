package kr.co.cudo.authoring.label.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * API-196 — 영상 라벨 <b>일괄 확정 저장</b> 요청.
 *
 * <h3>왜 영상 단위인가 (Critical)</h3>
 * 앞선 산출 회차를 불러온 뒤 <b>일부 프레임만</b> 저장하면 한 영상 안에 서로 다른 시점의 프레임이 섞인
 * 채로 확정되고, 그 상태가 그대로 학습데이터 산출물·관제로 나간다. 그래서 확정은 프레임 하나가 아니라
 * 영상 전체를 <b>한 트랜잭션</b>으로 처리한다. 프레임 단위 저장
 * ({@code PUT /v1/frames/{srcSn}/labels})은 평상시 편집용으로 <b>그대로 유지</b>되며 두 축을 합치지 않는다.
 *
 * <h3>{@code lblVer} 는 필수다 — 전수 검증</h3>
 * 보낸 프레임 가운데 <b>하나라도</b> 판번호가 현재 값과 다르면 그 사이 다른 사람이 먼저 저장한 것이므로
 * 영상 전체를 {@code 409} 로 거부한다. 편집하지 않은 프레임도 이 검증을 위해 함께 보낸다.
 * 프레임 단위 계약이 {@code labelVersion} 을 <b>선택</b>으로 둔 것(FE 미반영 구간 하위호환)과 달리 여기서는
 * 필수다 — 이 경로는 애초에 불러오기(API-195)가 판번호를 함께 내려준 뒤에만 쓰이고, 검사를 건너뛰면
 * full-replace 가 영상 전체에서 남의 라벨을 조용히 지운다.
 *
 * @param frames        저장할 프레임 목록(영상에 속한 프레임과 그 라벨 전체)
 * @param loadedVersion 이 편집을 어느 산출 회차에서 시작했는지 — 불러오기를 거치지 않고 작업본에서 바로
 *                      편집했으면 비운다. <b>기록용이며 저장 여부를 가르지 않는다.</b>
 * @design API-196
 * @req R6
 */
public record VideoLabelSaveRequest(
        @NotNull(message = "frames 는 필수입니다.")
        @NotEmpty(message = "저장할 프레임이 없습니다.")
        @Size(max = MAX_FRAMES_HARD_CAP, message = "한 번에 저장 가능한 프레임 수 초과")
        @Valid List<Frame> frames,
        /**
         * 시작 회차 번호(문자열). <b>숫자만 허용</b>한다 — 이 값은 감사 이력({@code RSN})에 실리므로
         * 자유 문자열을 받으면 개행·제어문자가 로그·이력 화면으로 흘러간다(CWE-117). 우리 계약에서
         * 회차는 정수이고(API-195 의 경로 변수) 표기를 둘로 두면 두 번째 진실원이 된다.
         */
        @Pattern(regexp = "\\A[0-9]{1,9}\\z", message = "loadedVersion 은 산출 회차 번호여야 합니다.")
        String loadedVersion
) {

    /**
     * DTO 레벨 <b>하드 캡</b> (CWE-770) — 역직렬화 단계에서 터무니없는 입력을 끊는다.
     *
     * <p>운영 상한은 설정값({@code authoring.version.start-version.max-frames})이며 서비스가 판정한다.
     * 여기 하드 캡을 함께 두는 이유는, 설정 상한 판정 전에 <b>요청 본문이 이미 힙에 올라와 있기</b>
     * 때문이다.
     *
     * <p><b>운영 기본값과 같은 수로 맞춘다</b> — 두 값이 벌어지면 "요청 1건이 처리할 수 있는 프레임 수"의
     * 상한이 두 곳으로 갈려, 설정을 조여도 하드 캡까지는 힙에 올라오는 구멍이 남는다. 설정을 기본값보다
     * <b>올린</b> 배포에서는 이 캡이 먼저 거부하는데, 그 방향(더 엄격)은 안전하고 자원 경계 목적에 맞다.
     */
    public static final int MAX_FRAMES_HARD_CAP = 2000;

    /**
     * 저장할 프레임 1건.
     *
     * @param srcSn  대상 프레임 식별자. <b>경로의 영상에 속한 프레임</b>이어야 한다(아니면 404 — CWE-639)
     * @param lblVer 화면이 읽어 간 시점의 그 프레임 라벨 판번호
     * @param items  그 프레임의 라벨 <b>전체</b>. 일부만 보내는 것이 아니라 통째로 바꾸는 방식이라,
     *               빈 배열을 보내면 그 프레임의 라벨이 모두 지워진다
     * @param dscdYn 프레임 폐기 여부({@code Y}/{@code N}). <b>보내지 않으면 지금 값을 그대로 둔다</b> —
     *               폐기를 모르는 호출자가 저장할 때마다 남의 폐기 결정을 되돌리지 않게 하는 규약이며,
     *               프레임 단위 저장({@link LabelBulkUpsertRequest#dscdYn()})과 <b>같은 축</b>이다
     */
    public record Frame(
            @NotNull(message = "srcSn 은 필수입니다.") Long srcSn,
            @NotNull(message = "lblVer 은 필수입니다.")
            @PositiveOrZero(message = "lblVer 은 0 이상이어야 합니다.") Long lblVer,
            @NotNull(message = "items 는 필수입니다.")
            @Size(max = 500, message = "한 프레임에 저장 가능한 라벨 수 초과 (최대 500)")
            @Valid List<LabelItemDto> items,
            @Pattern(regexp = "\\A[YN]\\z", message = "dscdYn 은 Y 또는 N 이어야 합니다.") String dscdYn
    ) {

        /** 프레임 단위 저장 계약으로 변환 — 저장 코어를 두 축이 공유하게 한다(로직 복제 금지). */
        public LabelBulkUpsertRequest toFrameRequest() {
            return new LabelBulkUpsertRequest(items, lblVer, dscdYn);
        }
    }
}
