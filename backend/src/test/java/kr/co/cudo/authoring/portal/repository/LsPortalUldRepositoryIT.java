package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.entity.LsPortalUldLbl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포털 업로드 3테이블(LS_PORTAL_ULD / _FRME / _LBL) 실 DB(PostgreSQL Testcontainer) 통합 테스트.
 *
 * <p>검증: 저장/조회 라운드트립, 소유자 스코프, (ULD_SN,FRME_NO) 유니크 제약, ON DELETE CASCADE.
 * 네이티브 CASCADE 삭제는 활성 트랜잭션이 필요하므로 {@code controlTransactionManager}
 * 기반 {@link TransactionTemplate} 안에서 실행한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LsPortalUldRepositoryIT {

    @Autowired
    private LsPortalUldRepository uldRepository;

    @Autowired
    private LsPortalUldFrmeRepository frmeRepository;

    @Autowired
    private LsPortalUldLblRepository lblRepository;

    private final TransactionTemplate txTemplate;

    LsPortalUldRepositoryIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @Test
    @DisplayName("포털업로드_엔티티_저장_조회_라운드트립_3테이블")
    void roundTrip_threeTables() {
        String user = "user-" + System.nanoTime();

        Long[] ids = txTemplate.execute(s -> {
            LsPortalUld uld = uldRepository.save(LsPortalUld.createVideo(
                    user, "clip.mp4", "/portal/clip.mp4", 2_000L, "video/mp4"));
            LsPortalUldFrme frme = frmeRepository.save(
                    LsPortalUldFrme.create(uld.getUldSn(), 1, "/portal/frames/1.jpg"));
            LsPortalUldLbl lbl = lblRepository.save(LsPortalUldLbl.create(
                    user, uld.getUldSn(), frme.getUldFrmeSn(),
                    LsPortalUldLbl.TYPE_BBOX, "car", "[10,20,30,40]"));
            return new Long[]{uld.getUldSn(), frme.getUldFrmeSn(), lbl.getUldLblSn()};
        });

        txTemplate.executeWithoutResult(s -> {
            assertThat(uldRepository.findByUldSnAndPortalUserNo(ids[0], user)).isPresent();
            assertThat(frmeRepository.findByUldFrmeSn(ids[1])).isPresent();
            List<LsPortalUldLbl> labels =
                    lblRepository.findByUldFrmeSnAndPortalUserNoOrderByRegDtDesc(ids[1], user);
            assertThat(labels).hasSize(1);
            assertThat(labels.get(0).getLblNm()).isEqualTo("car");
            assertThat(labels.get(0).getPointCn()).isEqualTo("[10,20,30,40]");
        });
    }

    @Test
    @DisplayName("소유자_스코프_조회는_타사용자_행을_반환하지_않음")
    void ownerScope_excludesOtherUser() {
        String owner = "owner-" + System.nanoTime();
        String other = "other-" + System.nanoTime();

        Long uldSn = txTemplate.execute(s -> uldRepository.save(
                LsPortalUld.createImage(owner, "a.jpg", "/p/a.jpg", 500L, "image/jpeg")).getUldSn());

        txTemplate.executeWithoutResult(s -> {
            assertThat(uldRepository.findByUldSnAndPortalUserNo(uldSn, owner)).isPresent();
            // 타 사용자로 조회 시 없음(IDOR 방지)
            assertThat(uldRepository.findByUldSnAndPortalUserNo(uldSn, other)).isEmpty();
        });
    }

    @Test
    @DisplayName("동일_업로드_동일_프레임번호_중복시_제약위반")
    void duplicateFrameNo_violatesUniqueConstraint() {
        Long uldSn = txTemplate.execute(s -> uldRepository.save(
                LsPortalUld.createVideo("u", "c.mp4", "/p/c.mp4", 1L, "video/mp4")).getUldSn());

        txTemplate.executeWithoutResult(s ->
                frmeRepository.save(LsPortalUldFrme.create(uldSn, 7, "/p/f7-a.jpg")));

        // 동일 (ULD_SN, FRME_NO) 재삽입 → UK 위반
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                frmeRepository.saveAndFlush(LsPortalUldFrme.create(uldSn, 7, "/p/f7-b.jpg"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("업로드_삭제시_프레임과_라벨이_연쇄_삭제됨")
    void deleteUpload_cascadesFramesAndLabels() {
        String user = "cascade-" + System.nanoTime();

        Long[] ids = txTemplate.execute(s -> {
            LsPortalUld uld = uldRepository.save(LsPortalUld.createVideo(
                    user, "c.mp4", "/p/c.mp4", 1L, "video/mp4"));
            LsPortalUldFrme frme = frmeRepository.save(
                    LsPortalUldFrme.create(uld.getUldSn(), 0, "/p/f0.jpg"));
            lblRepository.save(LsPortalUldLbl.create(
                    user, uld.getUldSn(), frme.getUldFrmeSn(),
                    LsPortalUldLbl.TYPE_POLYGON, "person", "[[1,2],[3,4]]"));
            return new Long[]{uld.getUldSn(), frme.getUldFrmeSn()};
        });

        // when — 업로드 삭제(DB ON DELETE CASCADE)
        txTemplate.executeWithoutResult(s -> {
            uldRepository.deleteById(ids[0]);
            uldRepository.flush();
        });

        // then — 프레임/라벨이 연쇄 삭제됨
        txTemplate.executeWithoutResult(s -> {
            assertThat(uldRepository.findById(ids[0])).isEmpty();
            assertThat(frmeRepository.findByUldFrmeSn(ids[1])).isEmpty();
            assertThat(lblRepository.findByUldFrmeSnAndPortalUserNoOrderByRegDtDesc(ids[1], user))
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("프레임_라벨_전체삭제_replace_all_대비_소유자_스코프")
    void deleteLabelsByFrame_ownerScoped() {
        String user = "del-" + System.nanoTime();

        Long frmeSn = txTemplate.execute(s -> {
            LsPortalUld uld = uldRepository.save(LsPortalUld.createImage(
                    user, "a.jpg", "/p/a.jpg", 1L, "image/jpeg"));
            LsPortalUldFrme frme = frmeRepository.save(
                    LsPortalUldFrme.createImageFrame(uld.getUldSn(), "/p/a.jpg"));
            lblRepository.save(LsPortalUldLbl.create(user, uld.getUldSn(), frme.getUldFrmeSn(),
                    LsPortalUldLbl.TYPE_BBOX, "b1", "[0,0,1,1]"));
            lblRepository.save(LsPortalUldLbl.create(user, uld.getUldSn(), frme.getUldFrmeSn(),
                    LsPortalUldLbl.TYPE_BBOX, "b2", "[1,1,2,2]"));
            return frme.getUldFrmeSn();
        });

        long deleted = txTemplate.execute(s ->
                lblRepository.deleteByUldFrmeSnAndPortalUserNo(frmeSn, user));

        assertThat(deleted).isEqualTo(2);
        txTemplate.executeWithoutResult(s ->
                assertThat(lblRepository.findByUldFrmeSnAndPortalUserNoOrderByRegDtDesc(frmeSn, user))
                        .isEmpty());
    }

    @Test
    @DisplayName("업로드_유형_필터_목록조회_동작")
    void listByType_filters() {
        String user = "list-" + System.nanoTime();
        txTemplate.executeWithoutResult(s -> {
            uldRepository.save(LsPortalUld.createImage(user, "a.jpg", "/p/a.jpg", 1L, "image/jpeg"));
            uldRepository.save(LsPortalUld.createVideo(user, "c.mp4", "/p/c.mp4", 1L, "video/mp4"));
        });

        txTemplate.executeWithoutResult(s -> {
            var images = uldRepository.findByPortalUserNoAndUldTypeCdOrderByRegDtDesc(
                    user, LsPortalUld.TYPE_IMAGE, org.springframework.data.domain.PageRequest.of(0, 10));
            assertThat(images.getContent()).allMatch(u -> u.getUldTypeCd().equals(LsPortalUld.TYPE_IMAGE));
            assertThat(images.getTotalElements()).isEqualTo(1);

            Optional<LsPortalUldFrme> none = frmeRepository.findByUldFrmeSnAndUldSn(-1L, -1L);
            assertThat(none).isEmpty();
        });
    }
}
