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
 *                        {@code JSON.parse} 로 5필드를 읽는다). 해상도 파생(내부 생성물)과 V147 이전
 *                        요청은 {@code null}.
 * @param resultState     <b>결과물 상태</b> — {@code framePairs} 가 비어 있는 이유를 화면이 사실대로
 *                        말할 수 있게 하는 축. {@link #STATE_GENERATING} ·
 *                        {@link #STATE_PREPARING_FRAMES} · {@link #STATE_READY} ·
 *                        {@link #STATE_WITHHELD} · {@link #STATE_GENERATION_FAILED} ·
 *                        {@link #STATE_CANCELED} 중 하나(항상 non-null).
 *                        <p>{@code decision} 만으로는 두 구분이 불가능해 신설했다:
 *                        ①<b>0장인데 정상</b>(반입이 비동기라 결정 시점엔 아직 없음) vs <b>영구 실패</b>
 *                        ②<b>생성 실패</b> vs <b>사람의 반려</b> — 둘 다 {@code decision=REJECTED} 라
 *                        FE 가 "거부됨(사유 없음)" 으로만 그렸다.
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
        String resultState
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
}
