package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3(트랙 관리 확장) — R4/R5 신규 트랙 범위 쿼리(LS_DATA_SRC.FRAME_NO JOIN) 검증.
 * <p>findByRawSnAndTrackIdFromFrameNo(범위 필터) + findDistinctTrackIdsByRawSn(채번) 정합.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataLblRepositoryTrackRangeTest {

    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private VideoRepository rawRepository;

    private Long rawSn;
    private Long s0;
    private Long s1;
    private Long s2;

    @BeforeEach
    void setup() {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-TRNG-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        s0 = srcRepository.save(LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();
        s1 = srcRepository.save(LsDataSrc.create(rawSn, 1, "1.jpg", LocalDateTime.now())).getSrcSn();
        s2 = srcRepository.save(LsDataSrc.create(rawSn, 2, "2.jpg", LocalDateTime.now())).getSrcSn();
        lblRepository.save(LsDataLbl.createAutoBbox(s0, null, "person", "[]", BigDecimal.ZERO, "5"));
        lblRepository.save(LsDataLbl.createAutoBbox(s1, null, "person", "[]", BigDecimal.ZERO, "5"));
        lblRepository.save(LsDataLbl.createAutoBbox(s2, null, "person", "[]", BigDecimal.ZERO, "5"));
        // 다른 트랙(7) — 채번/범위 필터에 섞이지 않아야 한다.
        lblRepository.save(LsDataLbl.createAutoBbox(s2, null, "car", "[]", BigDecimal.ZERO, "7"));
        lblRepository.flush();
    }

    @Test
    @DisplayName("fromFrameNo_이상_해당트랙만_프레임오름차순")
    void rangeFilter() {
        var range = lblRepository.findByRawSnAndTrackIdFromFrameNo(rawSn, "5", 1L);
        assertThat(range).extracting(LsDataLbl::getSrcSn).containsExactly(s1, s2);
        // fromFrameNo=0 → 전체(3), 999 → 없음.
        assertThat(lblRepository.findByRawSnAndTrackIdFromFrameNo(rawSn, "5", 0L)).hasSize(3);
        assertThat(lblRepository.findByRawSnAndTrackIdFromFrameNo(rawSn, "5", 999L)).isEmpty();
    }

    @Test
    @DisplayName("distinct_trackId_NULL제외_채번소스")
    void distinctTrackIds() {
        // TRCK_ID NULL 라벨은 결과에서 제외.
        lblRepository.save(LsDataLbl.createAutoBbox(s0, null, "dog", "[]", BigDecimal.ZERO, null));
        lblRepository.flush();
        assertThat(lblRepository.findDistinctTrackIdsByRawSn(rawSn))
                .containsExactlyInAnyOrder("5", "7");
    }
}
