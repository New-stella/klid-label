package kr.co.cudo.authoring.label.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * API-196 (v4) — 영상 라벨 <b>일괄 확정 저장</b> 요청.
 *
 * <h3>구조 — 회차 + 전 프레임 판번호 + 고친 프레임만</h3>
 * <pre>
 *   loadedVersion  : 확정할 산출 회차 번호(정수, 필수)
 *   frameVersions  : 영상 <b>전 프레임</b>의 판번호 (srcSn, lblVer)
 *   edits          : 사람이 <b>실제로 고친</b> 프레임만 (본문·폐기여부)
 * </pre>
 *
 * <h3>서버가 회차 스냅샷을 직접 읽는다 — 그것이 이 구조의 핵심이다</h3>
 * 확정 저장은 ①{@code loadedVersion} 스냅샷을 읽어 전 프레임에 적용하고 ②{@code edits} 에 온 프레임만
 * 그 내용으로 덮는다. 그래서 <b>사람이 그린 것인지 자동으로 붙은 것인지와 추적 식별자가 회차에 적힌
 * 대로 살아남는다</b>.
 *
 * <p><b>요청에 provenance(자동라벨 여부·신뢰도·출처) 필드를 두지 않는다</b> — 클라이언트가 "이건 AI 가
 * 만들었다"고 주장할 수 있게 하면 신뢰경계가 열린다(Mass Assignment, CWE-915). 그 값의 출처는 서버가
 * 읽는 회차 스냅샷 하나다.
 *
 * <h3>전 프레임 판번호가 필수인 이유</h3>
 * 보낸 프레임 가운데 <b>하나라도</b> 판번호가 어긋나면 그 사이 다른 사람이 먼저 저장한 것이므로 영상
 * 전체를 {@code 409} 로 거부한다. 그리고 {@code frameVersions} 가 <b>전 프레임을 덮지 않으면 400</b> 이다 —
 * 일부만 확정하면 한 영상 안에 서로 다른 시점의 프레임이 섞인 채로 외부(학습데이터 산출물·관제)로
 * 나가기 때문이다. <b>폐기된 프레임도 전 프레임에 포함</b>된다(불러오기가 전 프레임을 돌려주므로 왕복이
 * 성립한다).
 *
 * <p>구 {@code frames}(프레임마다 본문 전량) 필드는 <b>폐기</b>됐다. 옛 형태만 담긴 요청은
 * {@code loadedVersion}·{@code frameVersions} 가 없어 자연히 {@code 400} 이 되므로, 조용히 일부만
 * 저장되는 경로가 생기지 않는다(별도 거부 분기 불필요).
 *
 * @design API-196
 * @req R6
 */
public record VideoLabelSaveRequest(
        /**
         * 확정할 산출 회차 번호.
         *
         * <p>정수 타입이라 감사 이력({@code RSN})으로 흘러가는 로그 인젝션 축(CWE-117)이 <b>타입 자체로</b>
         * 닫힌다(구 규격의 "숫자 문자열" 제한이 이것으로 대체됐다). 그 영상에 <b>실재하는 회차</b>인지는
         * 서비스가 대조하며, 아니면 400 이다.
         */
        @NotNull(message = "loadedVersion 은 필수입니다.")
        @Positive(message = "loadedVersion 은 1 이상이어야 합니다.") Integer loadedVersion,

        /** 영상 전 프레임의 판번호. 편집하지 않은 프레임도 판번호 확인을 위해 함께 보낸다. */
        @NotNull(message = "frameVersions 는 필수입니다.")
        @NotEmpty(message = "저장할 프레임이 없습니다.")
        @Size(max = MAX_FRAMES_HARD_CAP, message = "한 번에 저장 가능한 프레임 수 초과")
        @Valid List<FrameVersion> frameVersions,

        /**
         * 사람이 실제로 고친 프레임만. 비어 있거나 없으면 <b>회차 스냅샷 그대로</b> 확정한다
         * (되돌리기만 하는 정상 동선이다).
         */
        @Size(max = MAX_FRAMES_HARD_CAP, message = "한 번에 저장 가능한 프레임 수 초과")
        @Valid List<FrameEdit> edits
) {

    /**
     * DTO 레벨 <b>하드 캡</b> (CWE-770) — 역직렬화 단계에서 터무니없는 입력을 끊는다.
     *
     * <p>운영 상한은 설정값({@code authoring.version.start-version.max-frames})이며 서비스가 판정한다.
     * 여기 하드 캡을 함께 두는 이유는, 설정 상한 판정 전에 <b>요청 본문이 이미 힙에 올라와 있기</b>
     * 때문이다.
     *
     * <p><b>운영 기본값과 같은 수로 맞춘다</b> — 두 값이 벌어지면 "요청 1건이 처리할 수 있는 프레임 수"의
     * 상한이 두 곳으로 갈려, 설정을 조여도 하드 캡까지는 힙에 올라오는 구멍이 남는다.
     */
    public static final int MAX_FRAMES_HARD_CAP = 2000;

    /** {@code edits} 를 보내지 않은 요청 — 회차 스냅샷 그대로 확정. */
    public List<FrameEdit> safeEdits() {
        return edits == null ? List.of() : edits;
    }

    /**
     * 프레임 1건의 판번호.
     *
     * @param srcSn  대상 프레임 식별자(경로의 영상에 속한 프레임이어야 한다 — 아니면 404, CWE-639)
     * @param lblVer 화면이 읽어 간 시점의 그 프레임 라벨 판번호
     */
    public record FrameVersion(
            @NotNull(message = "srcSn 은 필수입니다.") Long srcSn,
            @NotNull(message = "lblVer 은 필수입니다.")
            @PositiveOrZero(message = "lblVer 은 0 이상이어야 합니다.") Long lblVer
    ) {
    }

    /**
     * 사람이 고친 프레임 1건.
     *
     * @param srcSn  대상 프레임 식별자
     * @param items  그 프레임의 라벨 <b>전체</b>(전량 교체). 빈 배열은 그 프레임 라벨 전량 삭제다
     * @param dscdYn 프레임 폐기 여부({@code Y}/{@code N}). <b>보내지 않으면 회차 스냅샷의 값</b>을 쓴다
     */
    public record FrameEdit(
            @NotNull(message = "srcSn 은 필수입니다.") Long srcSn,
            @NotNull(message = "items 는 필수입니다.")
            @Size(max = 500, message = "한 프레임에 저장 가능한 라벨 수 초과 (최대 500)")
            @Valid List<LabelItemDto> items,
            @Pattern(regexp = "\\A[YN]\\z", message = "dscdYn 은 Y 또는 N 이어야 합니다.") String dscdYn
    ) {
    }
}
