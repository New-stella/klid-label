package kr.co.cudo.authoring.batch.vlm;

import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * "이 영상에 <b>시계열</b> 메타가 이미 적재됐는가" 판정의 <b>단일 원천</b> (@req R1).
 *
 * <h3>왜 별도 빈인가 (복제 금지)</h3>
 * <p>이 판정은 두 곳이 쓴다 — 보류 재개 러너({@code VlmWithheldResumeRunner.isWithheld})의 멱등 조건과,
 * 배치 재실행 시 {@code VlmTimeseriesStep} 의 <b>중복 외부 위탁 차단</b>이다. 두 곳이 각자
 * {@code countTimeseriesByRawSn(...)} 를 호출하면 한쪽만 갱신되는 날 조용히 어긋난다.
 *
 * <h3>⚠ 전체 카운트로 판정하면 안 된다</h3>
 * <p>{@code LS_DATA_META} 에는 VLM 시계열 메타와 {@code video.*} 기술메타({@code VideoMetaService} 소유)가
 * <b>같은 테이블</b>에 들어 있다. 전체 카운트({@code countByRawSn})로 판정하면 <b>기술메타만 있고 시계열은
 * 0건</b>인 영상(= 위탁이 필요한 바로 그 상태)이 "메타 이미 있음"으로 오산입돼 영구히 skip 된다.
 * 그래서 기술메타 접두 상수를 그대로 넘기는 {@link LsDataMetaRepository#countTimeseriesByRawSn} 만 쓴다
 * (접두 문자열을 여기서 복제하지 않는다).
 */
@Component
@RequiredArgsConstructor
public class VlmTimeseriesMetaPresence {

    private final LsDataMetaRepository metaRepository;

    /**
     * 시계열 메타 건수(기술메타 제외). {@code rawSn} 이 null 이면 0.
     *
     * <p>판정 근거를 로그에 남기는 호출부가 있어 boolean 이 아니라 건수도 노출한다.
     *
     * <p>트랜잭션 경계를 두지 않는다 — 호출부는 스텝 트랜잭션 안(배치) 또는 트랜잭션 밖({@code @Async}
     * 재개 러너)이며, 단건 카운트라 리포지토리 자체 트랜잭션으로 충분하다(중첩 커넥션 요구 금지).
     */
    public long count(Long rawSn) {
        if (rawSn == null) {
            return 0L;
        }
        return metaRepository.countTimeseriesByRawSn(rawSn, VideoMetaService.KEY_PREFIX);
    }

    /** 시계열 메타가 1건이라도 있는가 — 있으면 재위탁은 중복 메타·중복 검수행을 만든다. */
    public boolean exists(Long rawSn) {
        return count(rawSn) > 0;
    }
}
