package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 영상 재생 길이 해석의 <b>DB read 전용</b> 협력자(MEDIUM-1 리소스 관리 수정).
 *
 * <p>{@link VideoDurationResolver} 가 ffprobe 서브프로세스를 <b>DB 커넥션을 보유하지 않은 채</b>
 * 실행하도록, 값싼 DB read(① {@code VDO_LEN_SEC} ② {@code video.duration_ms} 메타)를 이 별도 빈의
 * <b>짧은 {@code REQUIRES_NEW}(readOnly)</b> 트랜잭션으로 분리한다. 이 메서드가 리턴하면 트랜잭션이
 * 완료(커밋)되어 커넥션이 즉시 풀에 반납되고, 이후 프로브는 활성 트랜잭션/커넥션 없이 수행된다.
 *
 * <p>별도 빈으로 둔 이유: 자기호출(self-invocation)은 스프링 트랜잭션 프록시를 우회하므로
 * {@code REQUIRES_NEW} 전파가 적용되지 않는다. 리졸버가 <b>다른 빈</b>의 이 메서드를 호출해야 프록시가
 * 개입해 새 짧은 트랜잭션이 생성된다.
 */
@Component
@RequiredArgsConstructor
public class VideoDurationDbReader {

    private final VideoRepository videoRepository;
    private final LsDataMetaRepository metaRepository;

    /**
     * 프로브 이전 단계에서 필요한 DB 값(원시 스칼라)을 <b>단일 짧은 read</b>로 모아 반환한다.
     * ms→초 환산·유효성 판정 등 파싱 로직은 리졸버가 담당하도록 여기서는 원시값만 넘긴다.
     *
     * @param rawSn 영상 PK. null 이면 {@code null} 반환.
     * @return DB read 결과. 영상이 없으면 {@code null}.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public DurationSource read(Long rawSn) {
        if (rawSn == null) {
            return null;
        }
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        if (raw == null) {
            return null;
        }
        String durationMsMeta = metaRepository.findByRawSnAndMetaKey(rawSn, VideoMetaService.KEY_DURATION_MS)
                .map(LsDataMeta::getMetaVl)
                .orElse(null);
        return new DurationSource(raw.getDurationSec(), durationMsMeta, raw.getRawFilePathNm());
    }

    /**
     * 재생 길이 해석에 필요한 원시 DB 값.
     *
     * @param durationSec     {@code LS_DATA_RAW.VDO_LEN_SEC}(적재 시 기록된 초). 미기입 시 null.
     * @param durationMsMeta  {@code LS_DATA_META.video.duration_ms}(적재 시 ffprobe 저장 ms 문자열). 없으면 null.
     * @param rawFilePathNm   원본 파일 경로(직접 프로브 대상). 없으면 null.
     */
    public record DurationSource(Integer durationSec, String durationMsMeta, String rawFilePathNm) {
    }
}
