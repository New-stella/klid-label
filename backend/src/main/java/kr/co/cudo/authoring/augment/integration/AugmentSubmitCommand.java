package kr.co.cudo.authoring.augment.integration;

import java.util.List;

/**
 * 외부 증강 위탁 1건(=job 1개)의 요청 컨텍스트 — 「생성형 AI API 연동명세서 v1.1」 §4.1 정합.
 *
 * <p>프레임이 {@code input_files} 상한(100)을 넘으면 한 증강 결과({@code originAugSn})가 여러
 * 커맨드로 분할된다. 그때 {@link #jobSeq} 는 1부터의 청크 순서, {@link #jobCount} 는 총 청크 수다.
 *
 * @param originAugSn PENDING 으로 커밋된 LS_DATA_AUG.DATA_AUG_SN (결과 매칭 키)
 * @param augType     증강 유형 (WINTER/NIGHT/RAIN)
 * @param requestId   본 도구가 발급한 request_id(= Idempotency-Key 헤더, ≤64). job 마다 고유.
 * @param evntType    관제 이벤트 유형 코드(LS_DATA_RAW.EVNT_TYPE_CD, ≤20)
 * @param requestUserId 요청자 식별자(토큰 sub, ≤64). null 허용.
 * @param callbackUrl 결과 회신 URL
 * @param inputFiles  비식별 프레임 경로 목록(1~100)
 * @param jobSeq      분할 위탁 순서(1부터)
 * @param jobCount    분할 위탁 총 개수
 */
public record AugmentSubmitCommand(
        Long originAugSn,
        String augType,
        String requestId,
        String evntType,
        String requestUserId,
        String callbackUrl,
        List<AugmentInputFile> inputFiles,
        int jobSeq,
        int jobCount) {

    public AugmentSubmitCommand {
        inputFiles = inputFiles == null ? List.of() : List.copyOf(inputFiles);
    }
}
