package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LsDataMetaRepository#countTimeseriesByRawSn} 실DB 계약 가드 (2026-08-03).
 *
 * <h3>왜 필요한가</h3>
 * <p>{@code LS_DATA_META} 에는 VLM 시계열 메타와 {@code video.*} 기술메타
 * ({@link VideoMetaService} 소유)가 <b>같은 테이블</b>에 들어 있다. VLM 보류 재개 판정
 * ({@code VlmWithheldResumeRunner})이 전체 카운트({@code countByRawSn})로 멱등을 따지면
 * <b>기술메타만 있고 시계열은 0건</b>인 영상 — 재개가 필요한 바로 그 상태 — 이 "메타 이미 있음"으로
 * 오산입돼 영구히 skip 되고, 시계열 메타가 무증상으로 영구 결손된다.
 *
 * <p>Mockito 단위 테스트는 JPQL {@code not like concat(:prefix, '%')} 가 실제로
 * {@link VideoMetaService#isTechnicalKey} 와 같은 집합을 가르는지 검증할 수 없다. 여기서 실 DB 로
 * 두 술어의 <b>동치</b>를 고정한다 — 접두 상수가 바뀌면 이 테스트가 먼저 깨진다.
 *
 * <p>공유 컨테이너 오염 방지를 위해 시드는 {@code TSC-} 고유 clipId 로 만들고 단언은 시드한 RAW 로만 좁힌다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataMetaTimeseriesCountIT {

    @Autowired LsDataMetaRepository metaRepository;
    @Autowired VideoRepository videoRepository;

    /** ffprobe/관제 인입이 채우는 기술메타 — 접두는 {@link VideoMetaService#KEY_PREFIX} 에서 파생한다. */
    private static final List<String> TECHNICAL_KEYS = List.of(
            VideoMetaService.KEY_PREFIX + "fps",
            VideoMetaService.KEY_PREFIX + "codec",
            VideoMetaService.KEY_PREFIX + "bit_rate",
            VideoMetaService.KEY_PREFIX + "duration_ms",
            VideoMetaService.KEY_PREFIX + "filesize",
            VideoMetaService.KEY_PREFIX + "resolution");

    private LsDataRaw seedRaw(String suffix) {
        return videoRepository.save(LsDataRaw.createFromIngest(
                "TSC-" + suffix, "CCTV-TSC", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/TSC-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
    }

    private long countTimeseries(Long rawSn) {
        return metaRepository.countTimeseriesByRawSn(rawSn, VideoMetaService.KEY_PREFIX);
    }

    @Test
    @DisplayName("실DB_기술메타만_있는_영상은_시계열카운트가_0이다_전체카운트는_양수")
    void technicalOnlyVideoCountsZeroTimeseries() {
        LsDataRaw raw = seedRaw("A");
        TECHNICAL_KEYS.forEach(k -> metaRepository.save(LsDataMeta.create(raw.getRawSn(), k, "v")));

        // 전체 카운트는 양수 — 이 값으로 판정하면 재개가 영구히 막힌다(결함 재현 축).
        assertThat(metaRepository.countByRawSn(raw.getRawSn())).isEqualTo(TECHNICAL_KEYS.size());
        assertThat(countTimeseries(raw.getRawSn())).isZero();
    }

    @Test
    @DisplayName("실DB_시계열메타는_기술메타와_섞여있어도_시계열만_세어진다")
    void countsOnlyTimeseriesWhenMixed() {
        LsDataRaw raw = seedRaw("B");
        TECHNICAL_KEYS.forEach(k -> metaRepository.save(LsDataMeta.create(raw.getRawSn(), k, "v")));
        metaRepository.save(LsDataMeta.create(raw.getRawSn(), "0-10", "차량 3대 진입"));
        metaRepository.save(LsDataMeta.create(raw.getRawSn(), "10-20", "보행자 횡단"));

        assertThat(countTimeseries(raw.getRawSn())).isEqualTo(2);
    }

    @Test
    @DisplayName("실DB_메타가_0건이면_시계열카운트도_0이다")
    void countsZeroWhenNoMeta() {
        LsDataRaw raw = seedRaw("C");

        assertThat(countTimeseries(raw.getRawSn())).isZero();
    }

    /**
     * 접두 상수 드리프트 가드 — JPQL {@code LIKE} 판정이 {@link VideoMetaService#isTechnicalKey} 와
     * <b>같은 집합</b>을 가르는지 실 DB 로 대조한다. 접두를 코드 어딘가에 복제하거나 상수를 바꾸면
     * (예: {@code "video_"} — LIKE 에서 {@code _} 는 임의 1문자) 이 단언이 먼저 깨진다.
     */
    @Test
    @DisplayName("실DB_LIKE판정이_isTechnicalKey술어와_동일집합을_가른다_유사키_과차단없음")
    void likePredicateMatchesIsTechnicalKey() {
        LsDataRaw raw = seedRaw("D");
        // 'video' 로 시작하지만 접두('video.')가 아닌 유사키는 시계열 메타다(과차단 금지).
        List<String> keys = List.of(
                VideoMetaService.KEY_PREFIX + "fps",
                "videoclip-0-10",
                "videoX0-10",
                "0-10");
        keys.forEach(k -> metaRepository.save(LsDataMeta.create(raw.getRawSn(), k, "v")));

        long expected = keys.stream().filter(k -> !VideoMetaService.isTechnicalKey(k)).count();
        assertThat(countTimeseries(raw.getRawSn())).isEqualTo(expected);
        assertThat(VideoMetaService.KEY_PREFIX).doesNotContain("%").doesNotContain("_");
    }
}
