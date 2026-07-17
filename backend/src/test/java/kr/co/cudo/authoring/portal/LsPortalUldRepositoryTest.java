package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.entity.LsPortalUldLbl;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldLblRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V107 포털 업로드 3테이블(ULD/FRME/LBL) 리포지토리 라운드트립 · 소유자 스코프 ·
 * 제약(UK/FK CASCADE) 검증. control DB(Testcontainer PostgreSQL, Flyway V107 적용).
 * <p>
 * {@code @Transactional} 로 각 테스트 격리(control TransactionManager 가 @Primary).
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class LsPortalUldRepositoryTest {

    @Autowired private LsPortalUldRepository uldRepository;
    @Autowired private LsPortalUldFrmeRepository frmeRepository;
    @Autowired private LsPortalUldLblRepository lblRepository;

    private static final String ALICE = "portal-alice-" + System.nanoTime();
    private static final String BOB = "portal-bob-" + System.nanoTime();

    @Test
    @DisplayName("포털업로드_엔티티_저장_조회_라운드트립")
    void roundTrip() {
        LsPortalUld uld = uldRepository.save(
                LsPortalUld.createVideo(ALICE, "clip.mp4", "/p/clip.mp4", 1024L, "video/mp4"));
        LsPortalUldFrme frme = frmeRepository.save(
                LsPortalUldFrme.create(uld.getUldSn(), 0, "/p/frames/0.jpg"));
        LsPortalUldLbl lbl = lblRepository.save(
                LsPortalUldLbl.create(ALICE, uld.getUldSn(), frme.getUldFrmeSn(),
                        LsPortalUldLbl.TYPE_BBOX, "car", "[[1,2],[3,4]]"));

        assertThat(uldRepository.findByUldSnAndPortalUserNo(uld.getUldSn(), ALICE)).isPresent();
        assertThat(frmeRepository.findByUldFrmeSnAndOwner(frme.getUldFrmeSn(), ALICE)).isPresent();
        assertThat(lblRepository.findAllByUldFrmeSnAndPortalUserNo(frme.getUldFrmeSn(), ALICE))
                .extracting(LsPortalUldLbl::getUldLblSn)
                .containsExactly(lbl.getUldLblSn());
    }

    @Test
    @DisplayName("소유자_스코프_조회는_타사용자_행을_반환하지_않음")
    void ownerScopeIsolation() {
        LsPortalUld aliceUld = uldRepository.save(
                LsPortalUld.createImage(ALICE, "a.jpg", "/p/a.jpg", 10L, "image/jpeg"));

        // 타사용자(BOB)로 조회 시 alice 업로드 미노출 (IDOR 차단)
        assertThat(uldRepository.findByUldSnAndPortalUserNo(aliceUld.getUldSn(), BOB)).isEmpty();

        Page<LsPortalUld> bobPage = uldRepository.findAllByPortalUserNo(BOB, PageRequest.of(0, 20));
        assertThat(bobPage.getContent())
                .extracting(LsPortalUld::getPortalUserNo)
                .doesNotContain(ALICE);
    }

    @Test
    @DisplayName("프레임_소유자_스코프_조회는_타사용자에게_빈결과")
    void frameOwnerScopeIsolation() {
        LsPortalUld uld = uldRepository.saveAndFlush(
                LsPortalUld.createVideo(ALICE, "s.mp4", "/p/s.mp4", 512L, "video/mp4"));
        LsPortalUldFrme frme = frmeRepository.saveAndFlush(
                LsPortalUldFrme.create(uld.getUldSn(), 0, "/p/s0.jpg"));

        // 소유자(ALICE)는 조회 성공, 타사용자(BOB)는 빈결과 (IDOR 차단)
        assertThat(frmeRepository.findByUldFrmeSnAndOwner(frme.getUldFrmeSn(), ALICE)).isPresent();
        assertThat(frmeRepository.findByUldFrmeSnAndOwner(frme.getUldFrmeSn(), BOB)).isEmpty();

        assertThat(frmeRepository.findAllByUldSnAndOwnerOrderByFrmeNo(
                uld.getUldSn(), ALICE, PageRequest.of(0, 20)).getContent()).hasSize(1);
        assertThat(frmeRepository.findAllByUldSnAndOwnerOrderByFrmeNo(
                uld.getUldSn(), BOB, PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("존재하지_않는_ULD_SN_라벨저장시_FK제약위반")
    void labelWithMissingUldViolatesFk() {
        LsPortalUld uld = uldRepository.saveAndFlush(
                LsPortalUld.createImage(ALICE, "f.jpg", "/p/f.jpg", 10L, "image/jpeg"));
        LsPortalUldFrme frme = frmeRepository.saveAndFlush(
                LsPortalUldFrme.create(uld.getUldSn(), 0, "/p/f0.jpg"));

        // 프레임은 유효하나 ULD_SN 이 존재하지 않으면 FK_LS_PORTAL_ULD_LBL_ULD 위반
        long missingUldSn = 9_999_999_999L;
        assertThatThrownBy(() ->
                lblRepository.saveAndFlush(LsPortalUldLbl.create(
                        ALICE, missingUldSn, frme.getUldFrmeSn(),
                        LsPortalUldLbl.TYPE_BBOX, "car", "[[1,2]]")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("동일_업로드_동일_프레임번호_중복시_제약위반")
    void duplicateFrameNoViolatesUnique() {
        LsPortalUld uld = uldRepository.save(
                LsPortalUld.createVideo(ALICE, "v.mp4", "/p/v.mp4", 2048L, "video/mp4"));
        frmeRepository.saveAndFlush(LsPortalUldFrme.create(uld.getUldSn(), 0, "/p/0.jpg"));

        assertThatThrownBy(() ->
                frmeRepository.saveAndFlush(LsPortalUldFrme.create(uld.getUldSn(), 0, "/p/dup.jpg")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("업로드_삭제시_프레임과_라벨이_연쇄_삭제됨")
    void cascadeDeleteOnUpload() {
        LsPortalUld uld = uldRepository.saveAndFlush(
                LsPortalUld.createVideo(ALICE, "c.mp4", "/p/c.mp4", 4096L, "video/mp4"));
        LsPortalUldFrme frme = frmeRepository.saveAndFlush(
                LsPortalUldFrme.create(uld.getUldSn(), 0, "/p/c0.jpg"));
        lblRepository.saveAndFlush(
                LsPortalUldLbl.create(ALICE, uld.getUldSn(), frme.getUldFrmeSn(),
                        LsPortalUldLbl.TYPE_POLYGON, "person", "[[0,0]]"));

        uldRepository.delete(uld);
        uldRepository.flush();

        // DB ON DELETE CASCADE 로 프레임/라벨이 연쇄 삭제 (SQL 재조회 = DB 진실)
        assertThat(frmeRepository.findAllByUldSnOrderByFrmeNo(uld.getUldSn())).isEmpty();
        List<LsPortalUldLbl> labels = lblRepository.findAllByUldSnAndPortalUserNo(uld.getUldSn(), ALICE);
        assertThat(labels).isEmpty();
    }
}
