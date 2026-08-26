package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 영상 <b>해상도 표시값</b> 해석기 — {@code LS_DATA_META} 의 {@code video.resolution} 을 읽는다.
 * [@design API-043] [@design SCREEN-009]
 *
 * <p>{@link VideoFpsResolver} 와 <b>같은 계층·같은 모양</b>이다. 두 값 모두 기술메타 테이블에만 있고
 * {@code LS_DATA_RAW} 에는 컬럼이 없다 — 메타 테이블이 <b>유일한 조달원</b>이다.
 *
 * <h3>⚠ fps 와 달리 폴백을 두지 않는다</h3>
 * <p>fps 는 미상 시 상수로 폴백한다. 그 값이 <b>마킹 frameIndex 계산의 입력</b>이라 서버와 화면이
 * 반드시 같은 값을 써야 하고, 값이 없다고 계산을 멈출 수는 없기 때문이다. 해상도는 <b>표시 전용</b>이라
 * 그 사정이 없다 — 없는 값을 지어내면 화면이 <b>사실이 아닌 해상도</b>를 실값처럼 보여준다.
 * 미상이면 {@code null} 을 내리고 화면이 「-」로 표시한다.
 *
 * <h3>형식을 여기서 재조립하지 않는다</h3>
 * <p>적재된 문자열({@code {가로}x{세로}})을 <b>그대로</b> 돌려준다. 값의 형식은 적재 지점
 * ({@code VideoMetaService})이 소유하며, 읽는 쪽이 파싱해 다시 조립하면 두 곳이 형식을 갖게 되어
 * 적재 형식이 바뀌는 날 조용히 어긋난다. 화면도 같은 이유로 재조립하지 않는다.
 *
 * <p><b>이름이 비슷한 {@code VideoResolutionService} 와 다른 것이다</b> — 그쪽은 표준 해상도
 * 파생영상을 <b>생성</b>하는 축이고, 이 클래스는 영상 자신의 해상도를 <b>읽어 보여주는</b> 축이다.
 */
@Component
@RequiredArgsConstructor
public class VideoResolutionResolver {

    private final LsDataMetaRepository metaRepository;

    /**
     * rawSn 영상의 해상도 표시값.
     *
     * @param rawSn 영상 PK ({@code LS_DATA_RAW.RAW_SN}). {@code null} 이면 {@code null}.
     * @return 적재된 {@code video.resolution} 값 그대로. 메타가 없거나 값이 비어 있으면 <b>{@code null}</b>
     *         — 빈 문자열이나 대체 문자를 내리지 않는다(그 표기는 화면의 몫이다).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public String resolveResolution(Long rawSn) {
        if (rawSn == null) {
            return null;
        }
        // 키 문자열을 여기서 복제하지 않는다 — 적재 지점의 상수를 그대로 참조해 한쪽만 바뀌는 일을 막는다.
        return metaRepository.findByRawSnAndMetaKey(rawSn, VideoMetaService.KEY_RESOLUTION)
                .map(LsDataMeta::getMetaVl)
                .filter(value -> !value.isBlank())
                .map(String::trim)
                .orElse(null);
    }
}
