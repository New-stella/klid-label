package kr.co.cudo.authoring.common.util;

/**
 * 프레임 위치와 초당 프레임 수로 <b>영상 내 시각(밀리초)</b> 을 구하는 단일 지점.
 *
 * <h3>왜 한 곳에 모으는가</h3>
 * <p>같은 식이 <b>프레임을 뽑는 쪽</b>({@code FfmpegFrameExtractor} 의 seek 위치)과
 * <b>화면에 보여주는 쪽</b>({@code VideoQueryService} 의 프레임 미리보기 시각) 두 곳에 복제돼 있었다.
 * 둘은 반드시 같은 값이어야 한다 — 화면이 "이 프레임은 12.34초 지점"이라고 말하는데 실제로 뽑힌 지점이
 * 다르면, 라벨 좌표가 붙는 장면과 사람이 보는 시각이 <b>조용히</b> 어긋난다(둘 다 정상 동작처럼 보인다).
 * 한쪽만 고치는 일이 구조적으로 불가능하도록 계산을 여기 하나로 둔다.
 *
 * <h3>왜 {@code Math.round} 인가</h3>
 * <p>프레임 위치를 시각으로 옮기면 대개 정수 밀리초가 나오지 않는다(예: 30fps 의 1번 프레임 =
 * 33.33…ms). 정수 절삭은 매번 <b>앞쪽으로만</b> 최대 1ms 씩 치우치는 반면 반올림은 참값에 가장 가깝다.
 * 실제 선택되는 프레임은 어느 쪽이든 같다(ffmpeg 가 seek 위치에서 최근접 프레임으로 snap 한다) —
 * 즉 이 선택은 정확도 개선이며 <b>구 정수 절삭으로 되돌리지 않는다</b>.
 *
 * <h3>여기는 순수 계산만 한다 — 미상 판정은 호출자 몫</h3>
 * <p>두 호출자의 <b>사전 조건이 서로 다르다</b>. 추출 쪽의 fps 는 자체 폴백을 거쳐 항상 유효하므로
 * 가드가 필요 없고, 표시 쪽은 위치가 없는 레거시 행({@code VDO_FRM_NO} NULL)과 비정상 fps 를
 * "미상"({@code null})으로 돌려줘야 한다. 그 판정을 이 메서드 안으로 들이면 추출 쪽 동작이 바뀌고,
 * 반대로 추출 쪽에 맞춰 판정을 없애면 표시 쪽이 회귀한다. 그래서 <b>공유하는 것은 식 하나뿐</b>이며
 * 미상 판정은 그것이 필요한 호출자가 자기 자리에서 한다.
 */
public final class VideoFrameTimeCalculator {

    /** 1초를 밀리초로 환산한 값. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    private VideoFrameTimeCalculator() {
    }

    /**
     * 영상 내 프레임 위치를 영상 시작점 기준 시각(밀리초)으로 환산한다.
     *
     * <p>값을 검사하지 않는다(위 클래스 javadoc "여기는 순수 계산만 한다" 참조). 음수 위치나
     * 0 이하·비유한수 fps 를 걸러야 하는 호출자는 <b>부르기 전에</b> 판정한다.
     *
     * @param videoFrameNo 영상 내 0-base 프레임 위치({@code VDO_FRM_NO} 또는 마킹의 frameIndex)
     * @param fps          초당 프레임 수. 호출자가 유효함을 보장한다
     * @return 영상 시작점 기준 시각(밀리초)
     */
    public static long millisAt(long videoFrameNo, double fps) {
        return Math.round(videoFrameNo * MILLIS_PER_SECOND / fps);
    }
}
