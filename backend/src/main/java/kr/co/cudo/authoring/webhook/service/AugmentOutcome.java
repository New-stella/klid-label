package kr.co.cudo.authoring.webhook.service;

/**
 * 증강 1건(= {@code LS_DATA_AUG} 1행)의 <b>최종 판정</b> — {@link AugmentResultService} 입력.
 *
 * <p>Phase 7-A2 에서 구 콜백 DTO({@code AugmentResultRequest}) 를 대체한다. 구 계약은 외부가
 * "증강 1건 = 콜백 1건" 을 보내는 구조라 웹훅 DTO 를 그대로 서비스에 넘겼지만, 새 계약(생성형 AI
 * v1.1)은 <b>job 단위</b> 웹훅이라 여러 job 을 집계한 뒤에야 증강 1건의 성패가 정해진다. 그래서
 * 수신 DTO 와 서비스 입력을 분리한다 — 서비스는 "어떤 프로토콜로 왔는지" 를 알 필요가 없다.
 *
 * @param dataAugSn     대상 {@code LS_DATA_AUG} PK
 * @param externalJobId 재전송 멱등 앵커로 적재할 외부 작업 ID
 *                      ({@code LS_DATA_AUG.OTSD_JOB_ID} UNIQUE)
 * @param success       전 job 성공 여부. false 면 {@code REJECTED} 로 종결하고 영상을 만들지 않는다
 * @param rawFilePathNm 결과 영상 경로(없으면 null → 부모 비식별 영상 경로로 폴백).
 *                      생성형 AI 는 이미지-to-이미지라 영상 파일을 돌려주지 않으므로 통상 null 이다
 */
public record AugmentOutcome(
        Long dataAugSn,
        String externalJobId,
        boolean success,
        String rawFilePathNm
) {

    public static AugmentOutcome succeeded(Long dataAugSn, String externalJobId) {
        return new AugmentOutcome(dataAugSn, externalJobId, true, null);
    }

    public static AugmentOutcome failed(Long dataAugSn, String externalJobId) {
        return new AugmentOutcome(dataAugSn, externalJobId, false, null);
    }
}
