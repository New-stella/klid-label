package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.MetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetaService.update 의 upsert(신규 등록/기존 수정) 단위 테스트 (Mockito).
 *
 * <p>Phase 4-A: 시계열 메타 신규 등록 — 기존 metaKey 가 없으면 신규 INSERT + 검토행(PENDING) 생성,
 * 있으면 값만 수정(회귀 없음). 동시 INSERT 는 원자적 upsert(ON CONFLICT)로 안전.
 */
class MetaServiceUpsertTest {

    private static final Long SRC_SN = 7001L;
    private static final Long RAW_SN = 8001L;
    private static final String ACTOR_SUB = "1001";
    private static final String NEW_KEY = "manual-timeseries";

    private LsDataMetaRepository metaRepository;
    private LsDataSrcRepository srcRepository;
    private LsTaskAssignmentRepository authrtRepository;
    private LsDataMetaReviewRepository metaReviewRepository;
    private ApplicationEventPublisher eventPublisher;
    private ReviewApprovalGate approvalGate;
    private MetaService service;

    @BeforeEach
    void setUp() {
        metaRepository = mock(LsDataMetaRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        authrtRepository = mock(LsTaskAssignmentRepository.class);
        metaReviewRepository = mock(LsDataMetaReviewRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        approvalGate = mock(ReviewApprovalGate.class);

        service = new MetaService(metaRepository, srcRepository, authrtRepository,
                metaReviewRepository, eventPublisher, approvalGate);

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
    }

    private TokenClaims reviewer() {
        return new TokenClaims(ACTOR_SUB, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataMeta metaWithSn(String key, String val, long metaSn) {
        LsDataMeta meta = LsDataMeta.create(RAW_SN, key, val);
        ReflectionTestUtils.setField(meta, "metaSn", metaSn);
        return meta;
    }

    @Test
    @DisplayName("기존메타_없을때_update가_신규_INSERT하고_검토행_생성")
    void 신규_등록_및_검토행_생성() {
        // given — 저장 전에는 없음, upsert 후 조회하면 신규 metaSn 부여된 행
        LsDataMeta saved = metaWithSn(NEW_KEY, "정차 이벤트", 500L);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, NEW_KEY))
                .thenReturn(Optional.empty())   // isNew 판정
                .thenReturn(Optional.of(saved)); // upsert 후 재조회
        when(metaReviewRepository.existsByDataMetaSn(500L)).thenReturn(false);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(saved));

        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(NEW_KEY, "정차 이벤트")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — 원자적 upsert 사용 (unique 위반 크래시 방지), 비원자 save 미사용
        verify(metaRepository).upsertMeta(RAW_SN, NEW_KEY, "정차 이벤트");
        verify(metaRepository, never()).save(any(LsDataMeta.class));

        // then — 신규 메타에 대해 PENDING 검토행 생성 (VLM 경로와 일관)
        ArgumentCaptor<LsDataMetaReview> captor = ArgumentCaptor.forClass(LsDataMetaReview.class);
        verify(metaReviewRepository).save(captor.capture());
        LsDataMetaReview review = captor.getValue();
        assertThat(review.getDataMetaSn()).isEqualTo(500L);
        assertThat(review.getDataRawSn()).isEqualTo(RAW_SN);
        assertThat(review.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING);
        assertThat(review.getMetaTypeCd()).isEqualTo(LsDataMetaReview.META_TYPE_EXTERNAL);
    }

    @Test
    @DisplayName("기존메타_있으면_값만_수정_회귀없음")
    void 기존_메타_값만_수정() {
        // given — 이미 존재하는 메타
        LsDataMeta existing = metaWithSn("event", "이동", 300L);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, "event"))
                .thenReturn(Optional.of(existing));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(existing));

        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("event", "정차")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — 값 수정 upsert 만, 신규 검토행 생성 없음(회귀 없음)
        verify(metaRepository).upsertMeta(RAW_SN, "event", "정차");
        verify(metaReviewRepository, never()).save(any(LsDataMetaReview.class));
    }

    @Test
    @DisplayName("동일_rawSn_metaKey_중복INSERT_경합시_안전_upsert")
    void 동시_INSERT_경합_안전() {
        // given — isNew 로 판정되나, 재조회 시 다른 트랜잭션이 먼저 넣은 행(검토행 이미 존재)
        LsDataMeta racedRow = metaWithSn(NEW_KEY, "정차", 900L);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, NEW_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(racedRow));
        when(metaReviewRepository.existsByDataMetaSn(900L)).thenReturn(true); // 이미 검토행 존재
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(racedRow));

        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(NEW_KEY, "정차")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — 원자적 upsert 로 unique 위반 크래시 없음, 중복 검토행 미생성(재사용)
        verify(metaRepository).upsertMeta(RAW_SN, NEW_KEY, "정차");
        verify(metaRepository, never()).save(any(LsDataMeta.class));
        verify(metaReviewRepository, never()).save(any(LsDataMetaReview.class));
    }
}
