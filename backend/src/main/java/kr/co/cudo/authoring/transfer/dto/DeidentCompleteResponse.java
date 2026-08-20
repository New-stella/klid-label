package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 비식별 완료 기록 결과(API-215).
 *
 * <h3>대응 수를 함께 돌려주는 이유</h3>
 * <p>이 기록은 폴더 위치만 남기는 데서 그치지 않고 그 폴더의 파일을 프레임에 이어 <b>각 프레임의 비식별
 * 이미지 위치</b>까지 채운다. 이름이 맞는 파일이 없는 프레임은 짐작으로 잇지 않고 비워 두는데, 그 사실을
 * 알리지 않으면 <b>비식별 이미지가 빠진 프레임이 몇 장인지 아무도 모르는 채</b> 승인으로 넘어간다.
 * {@code deidentFrameUnmatchedCount} 가 0 이 아니면 그만큼의 프레임이 비식별 이미지 없이 남는다.
 *
 * @param rawSn                      기록된 영상의 식별번호
 * @param procLogSn                  이번에 남긴 비식별 처리 이력의 식별번호
 * @param approvalHoldReleased       이 기록으로 검수 승인 보류가 풀렸는지 여부
 * @param deidentFrameMatchedCount   이름이 맞는 파일을 찾아 비식별 이미지 위치를 채운 프레임 수
 * @param deidentFrameUnmatchedCount 이름이 맞는 파일이 없어 비워 둔 프레임 수
 * @design API-215
 */
@Schema(description = "비식별 완료 기록 결과")
public record DeidentCompleteResponse(long rawSn,
                                      long procLogSn,
                                      boolean approvalHoldReleased,
                                      int deidentFrameMatchedCount,
                                      int deidentFrameUnmatchedCount) {
}
