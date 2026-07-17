package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.TrackDeleteResponse;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.TrackEditService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3(트랙 관리 확장) — R4 트랙 삭제 <b>실 FK</b> 통합 테스트 (@SpringBootTest + Testcontainers PostgreSQL).
 *
 * <p>순수 Mockito 단위 테스트가 못 잡는 CRITICAL 결함을 실 DB FK 로 발화시켜 검증한다:
 * 속성값(LS_DATA_LBL_ATTR_VAL, 실 FK {@code FK_LS_DATA_LBL_ATTR_LBL}→LS_DATA_LBL)이 붙은 라벨을
 * 포함한 트랙을 삭제해도 FK 위반 500 없이 ATTR_VAL → AI_INFO → LBL 이 모두 삭제되는지 확인.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class TrackEditServiceIntegrationTest {

    private static final String TRACK = "5";

    @Autowired private TrackEditService trackEditService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataLblAiInfoRepository aiInfoRepository;
    @Autowired private LsDataLblAttrValRepository attrValRepository;
    @Autowired private LsLabelRepository labelMasterRepository;
    @Autowired private LsLabelAttrRepository labelAttrRepository;

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    @Test
    @DisplayName("속성값_붙은_라벨_포함_트랙삭제_FK위반없이_ATTR_VAL·AI_INFO·LBL_모두삭제")
    void deletesTrackWithAttrValuesWithoutFkViolation() {
        // given — 영상 + 3 프레임 + 트랙 "5" 라벨 3건.
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-TE-FK-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
        Long rawSn = raw.getRawSn();
        Long s0 = srcRepository.save(LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();
        Long s1 = srcRepository.save(LsDataSrc.create(rawSn, 1, "1.jpg", LocalDateTime.now())).getSrcSn();
        Long s2 = srcRepository.save(LsDataSrc.create(rawSn, 2, "2.jpg", LocalDateTime.now())).getSrcSn();
        lblRepository.save(LsDataLbl.createAutoBbox(s0, null, "person", "[]", BigDecimal.ZERO, TRACK));
        LsDataLbl mid = lblRepository.save(LsDataLbl.createAutoBbox(s1, null, "person", "[]", BigDecimal.ZERO, TRACK));
        lblRepository.save(LsDataLbl.createAutoBbox(s2, null, "person", "[]", BigDecimal.ZERO, TRACK));

        // 자식 — 삭제 대상(frame>=1) 라벨에 AI_INFO + 실 FK 속성값(ATTR_VAL) 부착.
        LsLabel master = labelMasterRepository.save(LsLabel.create("person", "#FF0000", "BBOX", 0, "test"));
        LsLabelAttr attr = labelAttrRepository.save(
                LsLabelAttr.create(master.getLabelId(), "색상", "TEXT", null, null, "Y", 0, "test"));
        attrValRepository.save(LsDataLblAttrVal.create(mid.getLblSn(), attr.getAttrId(), "빨강"));
        aiInfoRepository.save(LsDataLblAiInfo.create(mid.getLblSn(), rawSn, s1,
                LsDataLblAiInfo.SRC_INTERPOLATE, BigDecimal.ZERO, "batch"));
        lblRepository.flush();

        // when — frame 1 이후 트랙 삭제. 속성값 붙은 라벨(mid)이 포함되어도 FK 위반 없이 삭제되어야 한다.
        TrackDeleteResponse res = trackEditService.deleteTrackFrom(rawSn, TRACK, 1, reviewer());

        // then — frame 1,2 두 건 삭제. 자식(ATTR_VAL/AI_INFO)까지 모두 제거.
        // (bulk 삭제 후라 findById 는 L1 캐시를 볼 수 있어 DB 를 치는 JPQL 쿼리로 검증한다.)
        assertThat(res.deletedCount()).isEqualTo(2);
        assertThat(lblRepository.findByRawSnAndTrackIdFromFrameNo(rawSn, TRACK, 1L)).isEmpty();
        assertThat(attrValRepository.findByLblSnIn(java.util.List.of(mid.getLblSn()))).isEmpty();
        assertThat(aiInfoRepository.findByDataLblSnIn(java.util.List.of(mid.getLblSn()))).isEmpty();
        // frame 0 라벨은 불변(범위 밖).
        assertThat(lblRepository.findByRawSnAndTrackId(rawSn, TRACK)).hasSize(1);
    }
}
