package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.label.dto.LabelResponse;

import java.util.List;

/**
 * API-195 — 산출 버전 하나를 영상 전체 범위로 <b>불러온</b> 결과(읽기 전용).
 *
 * <h3>이 응답은 확정이 아니다</h3>
 * 서버에는 아무것도 쓰지 않는다. 화면이 이 내용을 올려 두고 사용자가 확인한 뒤 저장(API-196
 * {@code PUT /v1/videos/{rawSn}/labels})을 눌렀을 때만 확정된다. 저장하지 않고 화면을 떠나면 작업본이
 * 그대로 남는다(SCREEN-005 · UC-008 · AC-008 ⑤).
 *
 * <h3>{@code lblVer} 를 함께 싣는 이유 (Critical)</h3>
 * 확정 저장이 프레임별 판번호를 <b>전수 검증</b>해 하나라도 어긋나면 영상 전체를 거부하는데(AC-008 ⑥),
 * 화면은 현재 프레임 하나만 개별 조회하므로 <b>나머지 프레임의 판번호를 알 방법이 없다</b>. 불러오기가
 * 영상 전 프레임을 읽는 유일한 지점이라 여기서 그때의 판번호를 함께 내려준다 — 없으면 그 수용 기준이
 * 구조적으로 충족 불가다. 이 값은 <b>스냅샷의 것이 아니라 지금 작업본의 것</b>이다(그사이 다른 사람이
 * 저장했는지 판정하는 축이므로).
 *
 * @param rawSn   대상 영상 식별자
 * @param version 불러온 승인 버전 번호(관제가 픽업하는 산출 폴더 {@code v{n}} 과 같은 번호)
 * @param frames  영상에 속한 프레임 전체. <b>폐기된 프레임도 빠지지 않는다</b>
 * @design API-195
 * @req R6
 */
public record VersionLabelsResponse(Long rawSn, Integer version, List<Frame> frames) {

    /**
     * 그 버전 시점의 프레임 1건.
     *
     * @param srcSn    프레임 식별자
     * @param frmNo    영상 안에서의 프레임 순번
     * @param dscdYn   그 버전을 확정할 때의 프레임 폐기 여부({@code Y}/{@code N}) — 버전 스냅샷이 폐기
     *                 상태를 함께 담으므로 불러오면 그때의 폐기 여부까지 화면에 올라온다
     * @param lblVer   <b>현재 작업본</b>의 라벨셋 판번호(확정 저장에 되돌려 보낼 낙관적 동시성 토큰)
     * @param resolved 그 버전 이하에 스냅샷이 있어 실제로 해석된 프레임인지. {@code false} 면
     *                 {@code items} 는 <b>현재 작업본</b>이고 폐기 여부도 현재 값이다 — 없는 과거를
     *                 추측해 라벨 0건으로 내려주면 그 위에서 저장할 때 남아 있던 라벨이 통째로 지워진다
     */
    public record Frame(Long srcSn, Integer frmNo, String dscdYn, Long lblVer,
                        boolean resolved, List<LabelResponse.Item> items) {
    }
}
