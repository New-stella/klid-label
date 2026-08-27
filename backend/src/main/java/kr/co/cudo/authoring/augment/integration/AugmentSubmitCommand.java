package kr.co.cudo.authoring.augment.integration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부 증강 위탁 1건(=job 1개)의 요청 컨텍스트 — 「생성형 AI API 연동명세서 v1.3」 §4.1 정합.
 *
 * <p>프레임이 {@code input_files} 상한(100)을 넘으면 한 증강 결과({@code originAugSn})가 여러
 * 커맨드로 분할된다. 그때 {@link #jobSeq} 는 1부터의 청크 순서, {@link #jobCount} 는 총 청크 수다.
 *
 * <p><b>{@link #evntType} 은 요청자가 화면에서 고른 값이다</b>(v1.3 정합, 2026-08-27). 구 구현은
 * 영상의 관제 이벤트 유형 코드({@code LS_DATA_RAW.EVNT_TYPE_CD})를 실었는데, 계약 허용값은
 * {@code FLOOD}/{@code WILDFIRE} 둘뿐이라 관제 코드를 그대로 보내면 {@code 400} 이다. 두 분류 축이
 * 다른 체계라 서버가 변환하면 그건 추정이므로, 요청 본문에서 받아 그대로 중계한다.
 *
 * @param originAugSn PENDING 으로 커밋된 LS_DATA_AUG.DATA_AUG_SN (결과 매칭 키)
 * @param augType     증강 유형 (WINTER/NIGHT/RAIN) — 저작도구 내부 식별자
 * @param mtdt        §4.1 {@code mtdt} — 요청자가 고른 생성 조건 5항목(허용 코드).
 *                    분할 위탁된 모든 청크가 <b>같은 값</b>을 실어야 한 요청의 결과가 균질해진다.
 * @param promptText  §4.1 {@code prompt} — 자유 지시문 문자열(≤1000, 선택). <b>객체가 아니다</b>
 * @param requestId   본 도구가 발급한 request_id(= Idempotency-Key 헤더, ≤64). job 마다 고유.
 * @param evntType    외부 이벤트 유형 — {@code FLOOD}/{@code WILDFIRE}
 * @param evntSubtype 침수 세부 유형(선택). 침수가 아니면 {@code null}
 * @param requestUserId 요청자 식별자(토큰 sub, ≤64). null 허용.
 * @param callbackUrl 결과 회신 URL
 * @param inputFiles  비식별 프레임 경로 목록(1~100)
 * @param jobSeq      분할 위탁 순서(1부터)
 * @param jobCount    분할 위탁 총 개수
 * @design INT-008
 */
public record AugmentSubmitCommand(
        Long originAugSn,
        String augType,
        Map<String, Object> mtdt,
        String promptText,
        String requestId,
        String evntType,
        String evntSubtype,
        String requestUserId,
        String callbackUrl,
        List<AugmentInputFile> inputFiles,
        int jobSeq,
        int jobCount) {

    public AugmentSubmitCommand {
        inputFiles = inputFiles == null ? List.of() : List.copyOf(inputFiles);
        // 키 순서 보존 복사 — 외부 JSON 이 명세서 샘플 순서로 읽히게 한다(AugmentRequestedItemEvent 동일).
        mtdt = mtdt == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(mtdt));
    }
}
