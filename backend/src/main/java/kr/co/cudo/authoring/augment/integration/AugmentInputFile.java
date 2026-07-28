package kr.co.cudo.authoring.augment.integration;

/**
 * 외부 증강 위탁 입력 파일 1건 — 「생성형 AI API 연동명세서 v1.1」 §4.1 {@code input_files[]}.
 *
 * <p><b>보안(PII)</b>: {@code filePath} 는 반드시 <b>비식별 프레임</b> 경로여야 한다. 원본(비-비식별)
 * 경로가 외부로 나가면 개인정보 유출이다. 경로 해석은
 * {@code AugmentJobSubmitService} 가 비식별 경로 컬럼만 조회해 수행하며, 비식별 경로가 없는
 * 프레임이 하나라도 있으면 위탁 자체를 거부한다(fail-closed, 원본 대체 금지).
 *
 * @param sequence 입력 순서(1부터, job 내 중복 불가)
 * @param filePath AI 가 접근 가능한 <b>비식별</b> 파일 절대경로(≤500)
 */
public record AugmentInputFile(int sequence, String filePath) {
}
