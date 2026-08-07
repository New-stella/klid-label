package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoService;
import kr.co.cudo.authoring.label.dto.LabelAttrValueResponse;
import kr.co.cudo.authoring.label.dto.LabelAttrValueUpsertRequest;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelAttrValueService;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.review.service.ReviewService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 비식별 누락 신고 게이트({@code DE_IDNTF_YN='F'}) <b>차단 범위 확대</b> 통합 테스트
 * (실 DB: Testcontainers PostgreSQL).
 *
 * <p>이미 412 로 막혀 있던 라벨 저장·조회, 개인정보 메타 저장과 <b>같은 축</b>인데 열려 있던 6경로를 닫는다:
 * <ol>
 *   <li>{@code GET  /v1/labels/{lblSn}/attrs} — 라벨 객체 속성값 조회</li>
 *   <li>{@code PUT  /v1/labels/{lblSn}/attrs} — 라벨 객체 속성값 저장</li>
 *   <li>{@code PUT  /v1/videos/{rawSn}/event-annotation} — 이벤트 어노테이션 저장</li>
 *   <li>{@code POST /v1/videos/{rawSn}/event-annotation/approve} — 승인</li>
 *   <li>{@code POST /v1/videos/{rawSn}/event-annotation/reject} — 반려</li>
 *   <li>{@code POST /v1/reviews/{videoId}/approve} — 검수 승인</li>
 * </ol>
 *
 * <p>각 경로마다 <b>①신고 구간이면 412 ②그때 값이 저장·전이되지 않는다 ③신고가 해소({@code 'F'→'Y'})되면
 * 별도 복원 절차 없이 정상 동작한다</b>를 한 테스트에서 고정한다. ③은 게이트가 <b>자동 해제</b>된다는 규약
 * (별도 복원 API 없음)의 회귀 가드이기도 하다.
 *
 * <p>모든 판정은 {@code DeidentReportGate} 단일 원천이며 응답은 {@link ErrorCode#PRECONDITION_FAILED}(412)로
 * 통일된다 — 역할 무관이므로 여기서는 <b>REVIEWER</b>(가장 권한이 넓은 역할)로 검증한다. REVIEWER 가 막히면
 * WORKER 도 막힌다.
 *
 * <p>트랜잭션 롤백(@Transactional)을 쓰지 않는다 — {@code 'F'→'Y'} 전이가 실제로 커밋돼야 게이트 해제를
 * 관측할 수 있으므로 {@link TransactionTemplate} 으로 명시 커밋하고, 각 테스트는 고유 영상을 새로 만든다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DeidentReportGateScopeIT {

    @Autowired private LabelAttrValueService labelAttrValueService;
    @Autowired private EvntAnnoService evntAnnoService;
    @Autowired private EvntAnnoReviewService evntAnnoReviewService;
    @Autowired private ReviewService reviewService;

    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsLabelRepository labelMasterRepository;
    @Autowired private LsLabelAttrRepository attrRepository;
    @Autowired private LsDataLblAttrValRepository valueRepository;
    @Autowired private LsEvntAnnoRepository annoRepository;
    @Autowired private LsEvntAnnoReviewRepository annoReviewRepository;
    @Autowired private ReviewRepository statusRepository;

    private final TransactionTemplate tx;

    DeidentReportGateScopeIT(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    private static TokenClaims reviewer() {
        return new TokenClaims("9101", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    // ────────────────────────────── seed helpers ──────────────────────────────

    /** 영상 1건 생성 (기본 {@code DE_IDNTF_YN='N'} → 게이트 통과). */
    private long seedVideo() {
        return tx.execute(s -> rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-DGATE-" + System.nanoTime(), "CCTV-1", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn());
    }

    /** 비식별 누락 신고 접수 — 게이트를 닫는다. */
    private void openDeidentReport(long rawSn) {
        tx.executeWithoutResult(s -> rawRepository.findById(rawSn).orElseThrow().markDeidentified("F"));
    }

    /** 신고 해소(재비식별 완료) — 게이트가 자동으로 열려야 한다. */
    private void resolveDeidentReport(long rawSn) {
        tx.executeWithoutResult(s -> rawRepository.findById(rawSn).orElseThrow().markDeidentified("Y"));
    }

    /** 라벨 마스터 + 속성 정의 + 그 마스터를 참조하는 라벨 객체를 만들고 (lblSn, attrId) 를 반환. */
    private long[] seedLabelObjectWithAttr(long rawSn) {
        return tx.execute(s -> {
            LsDataSrc frame = srcRepository.save(
                    LsDataSrc.create(rawSn, 0, "/var/raw/f0.jpg", LocalDateTime.now()));
            LsLabel master = labelMasterRepository.save(
                    LsLabel.create("사람-" + System.nanoTime(), "#FF0000", "BBOX", 0, "gate-it"));
            LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(
                    master.getLabelId(), "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 0, "gate-it"));
            LsDataLbl label = labelRepository.save(LsDataLbl.createManual(
                    frame.getSrcSn(), "BBOX", master.getLabelId(), master.getLabelNm(),
                    "[[1.0,1.0],[2.0,2.0]]", 9101L));
            return new long[]{label.getLblSn(), attr.getAttrId()};
        });
    }

    private static EventAnnotationPayload payload(String eventClass) {
        return new EventAnnotationPayload(eventClass, "무슨 이벤트인가?", null, null, null);
    }

    /** event_annotation + 검토행(AUTO_GENERATED)을 만든다. */
    private void seedEventAnnotation(long rawSn, String eventClass) {
        evntAnnoService.upsert(rawSn, payload(eventClass), reviewer());
    }

    private String currentAnnoContent(long rawSn) {
        return tx.execute(s -> annoRepository.findByRawSn(rawSn).map(LsEvntAnno::getAnnoCn).orElse(null));
    }

    private String currentAnnoReviewStatus(long rawSn) {
        return tx.execute(s -> annoRepository.findByRawSn(rawSn)
                .flatMap(a -> annoReviewRepository.findByEvntAnnoSn(a.getEvntAnnoSn()).stream().findFirst())
                .map(LsEvntAnnoReview::getRvwSttsCd)
                .orElse(null));
    }

    /** IN_REVIEW 상태 + 라벨 1건 보유 영상(검수 승인 가능 조건)을 만든다. */
    private void seedInReviewWithLabel(long rawSn) {
        tx.executeWithoutResult(s -> {
            LsDataSrc frame = srcRepository.save(
                    LsDataSrc.create(rawSn, 0, "/var/raw/f0.jpg", LocalDateTime.now()));
            labelRepository.save(LsDataLbl.createManual(frame.getSrcSn(), "BBOX", null,
                    "person", "[[1.0,1.0],[2.0,2.0]]", 9101L));
            LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
            status.transitionTo(LsRawDataStatus.STTS_PENDING);
            status.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
            statusRepository.save(status);
        });
    }

    private String currentVideoStatus(long rawSn) {
        return tx.execute(s -> statusRepository.findByRawDataId(rawSn).orElseThrow().getDataSttsCd());
    }

    /** 저장된 속성값 조회(게이트를 거치지 않는 직접 조회 — 저장 여부 자체를 본다). */
    private List<LsDataLblAttrVal> storedValues(long lblSn) {
        List<LsDataLblAttrVal> values = tx.execute(s -> valueRepository.findByLblSnOrderByAttrIdAsc(lblSn));
        return values == null ? List.of() : values;
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ────────────────────────── ① 속성값 조회 ──────────────────────────

    @Test
    @DisplayName("라벨_객체_속성값_조회는_신고_구간이면_412이고_해소되면_보존된_값을_그대로_돌려준다")
    void attrValueLookupBlockedWhileReportOpen() {
        long rawSn = seedVideo();
        long[] ids = seedLabelObjectWithAttr(rawSn);
        long lblSn = ids[0];
        long attrId = ids[1];
        tx.executeWithoutResult(s -> valueRepository.save(LsDataLblAttrVal.create(lblSn, attrId, "yes")));

        openDeidentReport(rawSn);

        assertThatThrownBy(() -> labelAttrValueService.findByLblSn(lblSn, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateScopeIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // 신고 해소 → 별도 복원 절차 없이 게이트가 열리고 보존된 값이 그대로 조회된다.
        resolveDeidentReport(rawSn);
        List<LabelAttrValueResponse> values = labelAttrValueService.findByLblSn(lblSn, reviewer());
        assertThat(values).hasSize(1);
        assertThat(values.get(0).value()).isEqualTo("yes");
    }

    // ────────────────────────── ② 속성값 저장 ──────────────────────────

    @Test
    @DisplayName("라벨_객체_속성값_저장은_신고_구간이면_412이고_값이_저장되지_않으며_해소되면_저장된다")
    void attrValueSaveBlockedWhileReportOpen() {
        long rawSn = seedVideo();
        long[] ids = seedLabelObjectWithAttr(rawSn);
        long lblSn = ids[0];
        long attrId = ids[1];

        openDeidentReport(rawSn);

        assertThatThrownBy(() -> labelAttrValueService.upsert(lblSn,
                List.of(new LabelAttrValueUpsertRequest.Entry(attrId, "yes")), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateScopeIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // 상태코드만이 아니라 <b>실제로 저장되지 않았음</b>을 확인한다.
        assertThat(storedValues(lblSn)).isEmpty();

        resolveDeidentReport(rawSn);
        labelAttrValueService.upsert(lblSn,
                List.of(new LabelAttrValueUpsertRequest.Entry(attrId, "yes")), reviewer());
        assertThat(storedValues(lblSn))
                .extracting(LsDataLblAttrVal::getValue)
                .containsExactly("yes");
    }

    // ────────────────────── ③ event_annotation 저장 ──────────────────────

    @Test
    @DisplayName("이벤트_어노테이션_저장은_신고_구간이면_412이고_기존_내용이_바뀌지_않으며_해소되면_저장된다")
    void eventAnnotationSaveBlockedWhileReportOpen() {
        long rawSn = seedVideo();
        seedEventAnnotation(rawSn, "정차");
        String before = currentAnnoContent(rawSn);
        assertThat(before).contains("정차");

        openDeidentReport(rawSn);

        assertThatThrownBy(() -> evntAnnoService.upsert(rawSn, payload("배회"), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateScopeIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // 거부됐으므로 payload 가 교체되지 않았다(부분 저장 없음).
        assertThat(currentAnnoContent(rawSn)).isEqualTo(before);

        resolveDeidentReport(rawSn);
        evntAnnoService.upsert(rawSn, payload("배회"), reviewer());
        assertThat(currentAnnoContent(rawSn)).contains("배회");
    }

    // ────────────────────── ④ event_annotation 승인 ──────────────────────

    @Test
    @DisplayName("이벤트_어노테이션_승인은_신고_구간이면_412이고_검토상태가_전이되지_않으며_해소되면_승인된다")
    void eventAnnotationApproveBlockedWhileReportOpen() {
        long rawSn = seedVideo();
        seedEventAnnotation(rawSn, "정차");
        String before = currentAnnoReviewStatus(rawSn);
        assertThat(before).isEqualTo(LsEvntAnnoReview.STTS_AUTO_GENERATED);

        openDeidentReport(rawSn);

        assertThatThrownBy(() -> evntAnnoReviewService.approve(rawSn, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateScopeIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // 검토 상태가 그대로다 — 확정 전이가 일어나지 않았다.
        assertThat(currentAnnoReviewStatus(rawSn)).isEqualTo(before);

        resolveDeidentReport(rawSn);
        evntAnnoReviewService.approve(rawSn, reviewer());
        assertThat(currentAnnoReviewStatus(rawSn)).isEqualTo(LsEvntAnnoReview.STTS_APPROVED);
    }

    // ────────────────────── ⑤ event_annotation 반려 ──────────────────────

    @Test
    @DisplayName("이벤트_어노테이션_반려는_신고_구간이면_412이고_검토상태가_전이되지_않으며_해소되면_반려된다")
    void eventAnnotationRejectBlockedWhileReportOpen() {
        long rawSn = seedVideo();
        seedEventAnnotation(rawSn, "정차");
        String before = currentAnnoReviewStatus(rawSn);

        openDeidentReport(rawSn);

        assertThatThrownBy(() -> evntAnnoReviewService.reject(rawSn, "근거 부족", reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateScopeIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        assertThat(currentAnnoReviewStatus(rawSn)).isEqualTo(before);

        resolveDeidentReport(rawSn);
        evntAnnoReviewService.reject(rawSn, "근거 부족", reviewer());
        assertThat(currentAnnoReviewStatus(rawSn)).isEqualTo(LsEvntAnnoReview.STTS_REJECTED);
    }

    // ────────────────────────── ⑥ 검수 승인 ──────────────────────────

    @Test
    @DisplayName("검수_승인은_신고_구간이면_412이고_상태가_전이되지_않으며_해소되면_승인된다")
    void reviewApproveBlockedWhileReportOpen() {
        long rawSn = seedVideo();
        seedInReviewWithLabel(rawSn);

        openDeidentReport(rawSn);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> reviewService.approve(rawSn, reviewer())))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateScopeIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // 전이 이전에 거부되므로 IN_REVIEW 가 그대로다 — 버전 스냅샷 동결·관제 통지가 시작되지 않는다.
        assertThat(currentVideoStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);

        resolveDeidentReport(rawSn);
        tx.executeWithoutResult(s -> reviewService.approve(rawSn, reviewer()));
        assertThat(currentVideoStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_APPROVED);
    }
}
