package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * API-196 — 영상 라벨 일괄 확정 저장 결과 요약.
 *
 * <p><b>라벨 본문을 되돌려주지 않는다</b> — 화면은 이미 그 내용을 들고 있고, 영상 전 프레임의 라벨을
 * 다시 실어 보내면 응답 크기가 요청과 같아진다. 대신 다음 저장에 필요한 <b>판번호</b>만 프레임별로
 * 돌려준다(이 값을 안 주면 화면이 곧바로 자기 자신과 409 가 난다).
 *
 * @param frames               프레임별 저장 결과
 * @param savedFrameCount      저장한 프레임 수
 * @param discardedFrameCount  저장 뒤 <b>폐기 상태로 남은</b> 프레임 수. 저작도구 화면의 프레임 총수에서는
 *                             빼지 않으며 학습데이터 산출물·데이터마트 노출에서만 빠진다(D2)
 * @design API-196
 * @req R6
 */
public record VideoLabelSaveResponse(Long rawSn, List<Frame> frames,
                                     int savedFrameCount, int discardedFrameCount) {

    /**
     * @param dscdYn 저장 뒤 폐기 여부
     * @param lblVer 저장 뒤 올라간 판번호 — 라벨이 실제로 바뀐 프레임만 올라간다(무변경 재저장은 그대로)
     */
    public record Frame(Long srcSn, String dscdYn, Long lblVer) {
    }
}
