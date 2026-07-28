package kr.co.cudo.authoring.augment.service;

/**
 * 위탁 입력 1건의 <b>순서↔프레임</b> 대응 — {@code LS_DATA_AUG_JOB_FILE} 선기록 재료(Phase 7-D).
 *
 * <p>외부에 보내는 {@code input_files[]} 항목({@link kr.co.cudo.authoring.augment.integration.AugmentInputFile})
 * 과 짝을 이루되, 외부로 나가지 않는 내부 식별자({@code srcSn})를 담는다. 결과 수신 시 이 대응으로
 * 산출물을 정확한 프레임에 되붙인다.
 *
 * @param fileSeq 위탁 입력 순서(= {@code AugmentInputFile.sequence})
 * @param srcSn   위탁한 비식별 프레임({@code LS_DATA_SRC}) 식별자
 */
public record AugmentJobFileRef(int fileSeq, Long srcSn) {
}
