package kr.co.cudo.authoring.augment.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 증강/파생 결과 항목 1건 — FE {@code AugmentResult} 계약 정합.
 *
 * <p><b>항목 = 증강 행 1건({@code DATA_AUG_SN})</b> 이다. 같은 (영상 × 종류) 재요청이 허용된
 * 2026-07-31 이후로는 같은 {@code type} 항목이 여러 건 나올 수 있다 — 항목을 구분하는 것은
 * {@code type} 이 아니라 {@code id} 와 {@link #prompt} 다.
 *
 * <p>수록 범위: <b>외부 위탁 증강(WINTER/NIGHT/RAIN)</b> + <b>해상도 파생(RESL_*)</b>. <b>둘 다</b>
 * {@code framePairs}(원본 비식별 ↔ 파생 프레임)를 채운다 — 구 구현은 외부 위탁 항목만 {@code List.of()}
 * 로 하드코딩해, 정작 <b>검수 대상인 유일한 유형</b>의 비교 이미지가 영구히 0장이었다. 상위 요구가
 * "증강 결과에서 <b>이미지를 비교해 보고</b> 사용 여부를 선택" 이므로 그 상태에서는 등재 게이트가
 * 의례적 절차가 된다.
 *
 * <p>항목 자체를 내리는 이유는 별개다 — REVIEWER 가 <b>채택/반려를 결정하기 전에</b> 그 결과물이 어떤
 * 조건({@link #prompt})으로 생성됐는지 확인할 수 있어야 한다(R9 역추적). 구 구현은 외부 위탁 항목을
 * 아예 제외해, 조건을 볼 수 있는 유일한 경로가 accept/reject <b>응답</b>뿐이었다(= 결정한 뒤에야,
 * 그것도 1회만 볼 수 있었다).
 *
 * @param id              결과 항목 ID = {@code LS_DATA_AUG.DATA_AUG_SN}
 * @param videoId         그룹 키 = <b>원본(부모) RAW_SN</b>. 해상도 3종이 한 영상 섹션의 탭으로 묶인다.
 * @param cctvName        원본 영상 CCTV 명 (미상 시 폴백)
 * @param type            증강/파생 유형 코드 (예: {@code RESL_720P})
 * @param framePairs      프레임 쌍(페이징된 슬라이스)
 * @param decision        활용 결정 상태. 해상도 파생은 검수 대상이 아니므로 항상 {@code ACCEPTED}(생성 완료)
 * @param decidedAt       생성 확정 일시(해상도 파생은 증강행 등록 일시)
 * @param rejectReason    반려 사유 (해상도 파생은 항상 null)
 * @param derivativeRawSn 파생 영상 RAW_SN — FE 가 파생본 라벨링/검수로 이동할 때 사용
 * @param totalFramePairs 페이징 전 전체 프레임 쌍 후보 수(파생 프레임 총 개수)
 * @param reviewable      accept/reject 가능 여부. 해상도 파생은 항상 false (내부 생성물)
 * @param prompt          이 결과물을 만들 때 외부로 전송한 생성 조건 원문({@code LS_DATA_AUG.PROMPT_CN}).
 *                        <b>보낸 그대로의 JSON 문자열</b>이며 서버가 재가공하지 않는다 — 역추적의 정본은
 *                        "실제로 나간 값" 이므로 파싱·재직렬화로 모양이 달라지면 근거가 약해진다(FE 가
 *                        {@code JSON.parse} 로 5필드를 읽는다). 해상도 파생(내부 생성물)과 V153 이전
 *                        요청은 {@code null}.
 * @param resultState     <b>결과물 상태</b> — {@code framePairs} 가 비어 있는 이유를 화면이 사실대로
 *                        말할 수 있게 하는 축. {@link #STATE_GENERATING} ·
 *                        {@link #STATE_PREPARING_FRAMES} · {@link #STATE_READY} ·
 *                        {@link #STATE_WITHHELD} · {@link #STATE_GENERATION_FAILED} ·
 *                        {@link #STATE_CANCELED} · {@link #STATE_PURGED} ·
 *                        {@link #STATE_DERIVATIVE_UNLINKED} 중 하나(항상 non-null).
 *                        <p><b>이 열거가 FE 계약의 정본이다</b> — 값을 추가하면 여기부터 갱신한다.
 *                        {@link #STATE_PURGED} 가 이 목록에서 누락돼 FE 가 6종만 보고 배선하던 적이
 *                        있다(DEV_FIX LOW).
 *                        <p>{@code decision} 만으로는 두 구분이 불가능해 신설했다:
 *                        ①<b>0장인데 정상</b>(반입이 비동기라 결정 시점엔 아직 없음) vs <b>영구 실패</b>
 *                        ②<b>생성 실패</b> vs <b>사람의 반려</b> — 둘 다 {@code decision=REJECTED} 라
 *                        FE 가 "거부됨(사유 없음)" 으로만 그렸다.
 * @param discard         <b>폐기(소프트 삭제) 상태</b> — 반려된 결과물의 유예 정보.
 *                        폐기 상태가 아니면(표식 없음 · 복구됨 · 해상도 파생) {@code null}.
 *                        <p>이 필드가 없던 동안 화면은 "언제 지워지는지" 도 "되돌릴 수 있는지" 도 알 수
 *                        없어 복구 API 가 사실상 도달 불가였다. 기존 컴포넌트 뒤에 <b>additive</b> 로
 *                        추가된 것이며 앞 필드의 의미·값은 변하지 않는다.
 *                        <p>⚠ <b>{@code discard.restorable} 은 복구 버튼의 근거가 아니다</b> — 그 값은
 *                        <b>폐기 표식 자체</b>의 상태 힌트({@code 열림 && 클레임 없음})이며, FE 는
 *                        {@link #restoreEligible} 만 쓴다(프로덕션 참조 0). 표식이 없는 반려를 표현할 수
 *                        없고 클레임 구간에서 보수적으로 {@code false} 를 내므로, 이 값으로 버튼을
 *                        그리면 <b>복구 가능한 항목의 버튼이 사라진다</b>. 그럼에도 제거하지 않는 이유는
 *                        ①하위호환(구 FE·외부 소비자) ②유예 안내 문구가 "지금 되돌릴 수 있는 표식인가"
 *                        를 표시하는 데 여전히 유효하기 때문이다. 두 값을 합치거나 서로 대체하지 말 것.
 * @param restoreEligible <b>이 항목에 복구(POST {@code /v1/augments/{id}/restore})를 시도할 수 있는가</b>
 *                        — 화면이 복구 버튼을 그리는 <b>유일한 근거</b>.
 *                        <p>{@link #decision} · {@link #resultState} · {@link #discard} 세 값으로 화면이
 *                        <b>재유도</b>하던 것을 대체한다(DEV_FIX HIGH-①). 재유도가 틀렸던 이유는
 *                        {@code decision=REJECTED} 가 <b>세 입력</b>(사람의 반려 · 폐기 표식 · 생성
 *                        영구 실패)에서 나오는데 복구 API 는 그중 <b>앞 둘만</b> 받아주기 때문이다 —
 *                        dead-letter 로 끝난 증강은 검수 행도 표식도 없어 <b>항상 404</b> 인데 화면은
 *                        버튼을 그렸고, 재조회해도 같은 값이 돌아와 무한 재시도가 됐다.
 *                        <p><b>계산 입력은 복구 API 의 사전조건과 같다</b>({@code AugmentDiscardService}):
 *                        <b>실삭제되지 않았고</b>({@code discard.purged != true}) <b>최신 검수 행이
 *                        {@code REJECTED} 다</b>. 표식({@code restore}) 경로와 표식 없는 폴백
 *                        ({@code restoreWithoutMark}) 경로는 <b>수용 조건이 같다</b> — 둘 다 마지막에
 *                        그 검수 행을 {@code REJECTED → PENDING} 으로 되돌리기 때문이다. "열린 표식이
 *                        있으면 복구 가능" 으로 넓히면 검수 행이 없는 창에서 <b>409 죽은 버튼</b>이
 *                        생긴다(DEV_FIX LOW-②).
 *                        <p>⚠ <b>{@code discard.restorable} 과 다른 축이다</b>(둘을 합치지 말 것).
 *                        <b>FE 가 버튼을 그리는 근거는 이 필드 하나</b>이고 저쪽은 프로덕션 FE 에서
 *                        참조하지 않는다. 저쪽은 <b>폐기 표식 자체</b>의 상태 힌트라 <b>표식이 없으면
 *                        필드째 없어</b>({@code discard=null}) 표식 없는 반려(그랜드퍼더링)를 표현할 수
 *                        없고, 실삭제 클레임({@code DEL_PRCS_DT})이 잡히면 보수적으로 {@code false} 를
 *                        낸다 — 그 상태에서도 복구는 실제로 성립하므로 이쪽은 {@code true} 다.
 *                        즉 <b>두 값이 갈리는 것이 정상</b>이며, 한쪽을 다른 쪽으로 대체하면 복구
 *                        가능한 항목의 버튼이 사라진다.
 *                        <p><b>최종 판정이 아니다</b> — 복구 API 가 행을 잠그고 다시 판정하므로, 버튼을
 *                        그린 뒤에도 그 사이 스윕이 커밋되면 409/404 가 날 수 있다. 인가 판단에 쓰지 말 것.
 *                        <p>해상도 파생은 검수·폐기 체계 밖이라 항상 {@code false}.
 */
public record AugmentResultItemResponse(
        Long id,
        Long videoId,
        String cctvName,
        String type,
        List<AugmentFramePairResponse> framePairs,
        String decision,
        LocalDateTime decidedAt,
        String rejectReason,
        Long derivativeRawSn,
        long totalFramePairs,
        boolean reviewable,
        String prompt,
        String resultState,
        AugmentDiscardStateResponse discard,
        boolean restoreEligible
) {

    /** 생성이 아직 진행 중 — 결과물(파생영상) 자체가 없다. */
    public static final String STATE_GENERATING = "GENERATING";
    /**
     * 생성은 끝났고 <b>비교 이미지 반입이 진행 중</b> — 프레임 쌍 0장이 <b>정상</b>인 구간.
     * 파생 프레임·라벨은 커밋 후 비동기로 채워진다({@code AsyncAugmentFrameRunner}).
     */
    public static final String STATE_PREPARING_FRAMES = "PREPARING_FRAMES";
    /** 비교 이미지가 실재한다. */
    public static final String STATE_READY = "READY";
    /** 파생영상이 비식별 누락 신고 구간이라 비교 이미지를 내보내지 않는다(CWE-359). */
    public static final String STATE_WITHHELD = "WITHHELD";
    /** 생성이 <b>영구 실패</b>(dead-letter)로 종결 — 기다려도 이미지가 생기지 않는다. */
    public static final String STATE_GENERATION_FAILED = "GENERATION_FAILED";
    /** 사용자 취소로 종결. */
    public static final String STATE_CANCELED = "CANCELED";
    /**
     * 반려 후 <b>유예가 지나 실삭제</b>됐다 — 비교 이미지가 영구히 없다.
     *
     * <p>실삭제된 항목은 프레임 쌍이 0장인데, 그 사실만으로는 {@link #STATE_PREPARING_FRAMES}
     * ("반입 중, 곧 옴")로 계산돼 <b>같은 응답 안에서 {@code discard.purged=true}("영영 없음")와
     * 정면으로 모순</b>된다. 그래서 이 상태를 <b>최우선</b>으로 판정한다.
     */
    public static final String STATE_PURGED = "PURGED";
    /**
     * <b>파생 영상 매핑({@code LS_DATA_AUG.NEW_RAW_SN})이 없어</b> 비교 이미지를 <b>영구히</b>
     * 제공할 수 없다 — V155 이전에 생성된 외부 위탁 증강(그랜드퍼더링).
     *
     * <h3>왜 별도 상태인가 (Phase 6 잔여 D-1)</h3>
     * <p>이 항목들은 파생 영상과 이어지는 단서가 없어 프레임 쌍이 <b>영원히 0장</b>인데,
     * 그 사실만으로는 {@link #STATE_PREPARING_FRAMES}("생성이 완료되어 비교 이미지를 반입하고
     * 있습니다")로 계산됐다. 화면은 그 값을 정직하게 옮겨, <b>영원히 오지 않을 것을 곧 온다고</b>
     * 말했다 — REVIEWER 가 무한정 기다리게 되는 거짓 안내다.
     *
     * <h3>{@link #STATE_GENERATION_FAILED} 로 재분류하지 말 것</h3>
     * <p>이 항목의 <b>생성은 성공했다</b>(파생 영상도 실재한다). 없는 것은 증강 행 ↔ 파생 영상을
     * 잇는 <b>매핑</b>뿐이다. 실패로 표시하면 다음 사람이 존재하지 않는 실패 원인을 찾게 된다.
     *
     * <h3>백필하지 않는다</h3>
     * <p>{@code NEW_RAW_SN} 역추정은 시각 기반이라 <b>다른 요청의 파생본</b>을 가리킨다(V155 가 이미
     * 폐기한 방법). 비교 이미지를 실제로 <b>보여주는 것</b>은 백필 없이는 불가능하지만, <b>거짓 문구를
     * 멈추는 데는 백필이 불필요</b>하다 — 매핑 부재는 지금 그대로 판별 가능한 사실이다.
     *
     * <h3>⚠ 외부 위탁 전용이다</h3>
     * <p>해상도 파생({@code RESL_*})은 매핑을 못 찾으면 결과 조회가 <b>항목 자체를 드롭</b>하므로
     * (총량 정합 불변식) 응답에 도달하는 해상도 항목에는 매핑이 항상 있다.
     *
     * <h3>⚠ {@code reviewable} 은 이 상태에서도 유지된다</h3>
     * <p>비교 이미지 0장인 항목의 승인은 형식적이지만, {@code reviewable=false} 로 막으면 이 항목들이
     * <b>영구히 등재되지 못해</b>(등재 게이트가 리뷰 축이다) 이미 배정된 WORKER 의 영상이 작업목록에서
     * 사라지는 <b>고아 배정</b>이 된다 — {@code CLAUDE.md} 가 그랜드퍼더링에서 경계한 상황 그대로다.
     */
    public static final String STATE_DERIVATIVE_UNLINKED = "DERIVATIVE_UNLINKED";
}
