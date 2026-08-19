package kr.co.cudo.authoring.dev.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [개발/검수 전용] 레거시 비식별 프레임 복구가 쓰는 <b>신규 조회·갱신 쿼리</b>를 실 DB 로 검증한다.
 *
 * <h3>왜 단위 테스트로 부족한가</h3>
 * <p>{@code restoreVideoFrameNo} 는 <b>native UPDATE</b> 라 Hibernate 가 기동 시점에 파싱하지
 * 않는다 — 컬럼명 오타·문법 오류가 컨텍스트 기동을 통과하고 <b>실행할 때</b> 터진다. 리포지토리를
 * 목으로 대체한 단위 테스트는 그 창을 전혀 덮지 못하므로, 실제 PostgreSQL 에 쏘아 본다.
 *
 * <p>{@code @Transactional} 롤백 경계라 테스트 데이터가 남지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class DeidentFrameRecoveryQueryIT {

    private static final String CLIP_PREFIX = "DEIDRECOVERY-IT-";

    @PersistenceContext(unitName = "control")
    private EntityManager em;

    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;

    @Test
    @DisplayName("VDO_FRM_NO_복원은_비어있을_때만_적용되고_재실행하면_0행이다")
    void restoreVideoFrameNoIsConditionalAndIdempotent() {
        // given — 레거시 프레임(VDO_FRM_NO NULL) 1건 + 이미 값이 있는 프레임 1건
        LsDataRaw raw = saveVideo("RESTORE");
        LsDataSrc legacy = saveFrame(raw.getRawSn(), 0, null, null);
        LsDataSrc filled = saveFrame(raw.getRawSn(), 1, 30L, null);
        em.flush();

        // when — 첫 적용
        int firstLegacy = srcRepository.restoreVideoFrameNo(legacy.getSrcSn(), 900L);
        // 이미 값이 있는 행은 덮지 않는다(기존 작업 결과 보호).
        int onFilled = srcRepository.restoreVideoFrameNo(filled.getSrcSn(), 999L);
        // 재실행(멱등)
        int secondLegacy = srcRepository.restoreVideoFrameNo(legacy.getSrcSn(), 900L);
        em.clear();

        // then
        assertThat(firstLegacy).isOne();
        assertThat(onFilled).isZero();
        assertThat(secondLegacy).isZero();
        assertThat(srcRepository.findById(legacy.getSrcSn()).orElseThrow().getVideoFrameNo()).isEqualTo(900L);
        assertThat(srcRepository.findById(filled.getSrcSn()).orElseThrow().getVideoFrameNo()).isEqualTo(30L);
    }

    @Test
    @DisplayName("비식별_프레임_경로가_빈_영상만_복구_대상으로_조회된다")
    void findsOnlyVideosMissingDeidFramePath() {
        // given — ①경로 없음(대상) ②빈 문자열(대상) ③경로 있음(비대상)
        LsDataRaw missing = saveVideo("MISSING");
        saveFrame(missing.getRawSn(), 0, 0L, null);
        LsDataRaw blank = saveVideo("BLANK");
        saveFrame(blank.getRawSn(), 0, 0L, "");
        LsDataRaw done = saveVideo("DONE");
        saveFrame(done.getRawSn(), 0, 0L, "/frames/deid/x/frame-0.jpg");
        em.flush();

        // when — 상한을 크게 잡아 심은 데이터가 모두 보이게 한다(기존 데이터와 섞여도 판정은 포함/불포함).
        List<Long> targets = srcRepository.findRawSnsMissingDeidFramePath(PageRequest.of(0, 1000));

        // then
        assertThat(targets).contains(missing.getRawSn(), blank.getRawSn());
        assertThat(targets).doesNotContain(done.getRawSn());
        assertThat(targets).isSorted();
        assertThat(srcRepository.countRawSnsMissingDeidFramePath())
                .isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("복구_대상_조회는_상한을_넘겨_읽지_않는다")
    void targetLookupRespectsPageLimit() {
        LsDataRaw a = saveVideo("CAP-A");
        saveFrame(a.getRawSn(), 0, 0L, null);
        LsDataRaw b = saveVideo("CAP-B");
        saveFrame(b.getRawSn(), 0, 0L, null);
        em.flush();

        assertThat(srcRepository.findRawSnsMissingDeidFramePath(PageRequest.of(0, 1))).hasSize(1);
    }

    // ------------------------------------------------------------------ fixtures

    private LsDataRaw saveVideo(String suffix) {
        return videoRepository.saveAndFlush(LsDataRaw.createFromIngest(
                CLIP_PREFIX + suffix, "CCTV-" + suffix, "EV01000101", "11110",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/" + CLIP_PREFIX + suffix + ".mp4",
                LocalDateTime.of(2026, 5, 10, 0, 0), 30));
    }

    private LsDataSrc saveFrame(Long rawSn, long frameNo, Long videoFrameNo, String deidPath) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, videoFrameNo,
                "/frames/raw/" + rawSn + "/frame-" + frameNo + ".jpg", LocalDateTime.now());
        if (deidPath != null) {
            src.attachDeidPath(deidPath);
        }
        return srcRepository.saveAndFlush(src);
    }
}
