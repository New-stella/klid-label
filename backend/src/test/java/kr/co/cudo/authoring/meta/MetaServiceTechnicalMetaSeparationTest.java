package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.dto.MetaResponse;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.MetaService;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code video.*} 기술메타를 시계열 메타에서 분리하는 회귀 가드 (2026-08-03 사용자 확정).
 *
 * <h3>왜 필요한가</h3>
 * <p>{@code video.fps}·{@code video.duration_ms} 등 6키는 ffprobe/관제 인입이 채우는 <b>영상 기술메타</b>
 * ({@link VideoMetaService} 소유)인데, VLM 시계열 메타와 <b>같은 테이블 {@code LS_DATA_META}</b> 에
 * 저장된다. 조회가 이를 구분하지 않아 검수 화면·라벨링 메타 탭이 기술메타를 "시계열 메타"로 렌더했다.
 *
 * <h3>계약</h3>
 * <ul>
 *   <li>{@code items} (시계열) 에는 {@code video.*} 가 <b>하나도 없다</b>.</li>
 *   <li>기술메타는 버리지 않고 {@code technicalMeta} 로 <b>여전히 조회된다</b>(정보 유실 없음).</li>
 *   <li>수정 경로는 {@code video.*} 를 <b>거부</b>한다 — 산문 덮어쓰기·신규 검토행 생성 차단.</li>
 * </ul>
 *
 * <p>판정은 {@link VideoMetaService#isTechnicalKey} 단일 술어를 재사용한다(접두 문자열 복제 금지).
 */
class MetaServiceTechnicalMetaSeparationTest {

    private static final Long SRC_SN = 7001L;
    private static final Long RAW_SN = 8001L;
    private static final String ACTOR_SUB = "1001";

    /** ffprobe/인입이 채우는 기술메타 6키 — 소비처 계약이므로 문자열 그대로 고정한다. */
    private static final List<String> TECHNICAL_KEYS = List.of(
            "video.fps", "video.codec", "video.bit_rate",
            "video.duration_ms", "video.filesize", "video.resolution");

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
        when(metaReviewRepository.findByDataMetaSnIn(anyCollection())).thenReturn(List.of());
    }

    private TokenClaims reviewer() {
        return new TokenClaims(ACTOR_SUB, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataMeta metaWithSn(String key, String val, long metaSn) {
        LsDataMeta meta = LsDataMeta.create(RAW_SN, key, val);
        ReflectionTestUtils.setField(meta, "metaSn", metaSn);
        return meta;
    }

    // ───────── R1: 조회 분리 ─────────

    @Test
    @DisplayName("시계열메타_조회에_video기술메타가_하나도_없다")
    void 시계열_목록에서_기술메타_제외() {
        // given — VLM 세그먼트 2건 + 기술메타 6건이 같은 rawSn 에 섞여 있다.
        List<LsDataMeta> all = new java.util.ArrayList<>();
        all.add(metaWithSn("0-10", "차량 3대 진입", 501L));
        all.add(metaWithSn("10-20", "보행자 횡단", 502L));
        long sn = 600L;
        for (String key : TECHNICAL_KEYS) {
            all.add(metaWithSn(key, "v" + sn, sn++));
        }
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(all);

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 시계열 목록엔 video.* 가 전무하고 VLM 세그먼트만 남는다.
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .containsExactly("0-10", "10-20")
                .noneMatch(VideoMetaService::isTechnicalKey);
    }

    @Test
    @DisplayName("기술메타6키는_technicalMeta로_여전히_조회된다_정보유실없음")
    void 기술메타_별도필드로_보존() {
        // given
        List<LsDataMeta> all = new java.util.ArrayList<>();
        all.add(metaWithSn("0-10", "차량 3대 진입", 501L));
        long sn = 600L;
        for (String key : TECHNICAL_KEYS) {
            all.add(metaWithSn(key, "val-" + key, sn++));
        }
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(all);

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 6키 전부 값과 함께 별도 필드로 반환(버리지 않는다).
        assertThat(res.technicalMeta()).extracting(MetaResponse.Item::metaKey)
                .containsExactlyInAnyOrderElementsOf(TECHNICAL_KEYS);
        assertThat(res.technicalMeta()).extracting(MetaResponse.Item::metaVal)
                .allMatch(v -> v.startsWith("val-video."));
    }

    @Test
    @DisplayName("기술메타만_있는_영상은_시계열_0건이고_technicalMeta만_반환")
    void 기술메타만_있는_영상() {
        // given — VLM 미수행 영상(대다수의 배치 직후 상태)
        List<LsDataMeta> all = new java.util.ArrayList<>();
        long sn = 600L;
        for (String key : TECHNICAL_KEYS) {
            all.add(metaWithSn(key, "v", sn++));
        }
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(all);

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then
        assertThat(res.items()).isEmpty();
        assertThat(res.technicalMeta()).hasSize(TECHNICAL_KEYS.size());
    }

    @Test
    @DisplayName("메타_0건이면_두_목록_모두_빈배열이고_null이_아니다")
    void 메타_0건_빈배열() {
        // given
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — FE 가 항상 배열로 다룰 수 있어야 한다(크래시 방지).
        assertThat(res.items()).isNotNull().isEmpty();
        assertThat(res.technicalMeta()).isNotNull().isEmpty();
    }

    // ───────── R3-B / 수용기준 6: 저장 경로 차단 ─────────

    @Test
    @DisplayName("기술메타키_수정요청은_400이고_upsert도_검토행생성도_없다")
    void 기술메타_수정_거부() {
        // given
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("video.fps", "산문으로 덮어쓰기 시도")));

        // when / then
        assertThatThrownBy(() -> service.update(SRC_SN, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), anyString());
        verify(metaReviewRepository, never()).save(any(LsDataMetaReview.class));
    }

    @Test
    @DisplayName("기술메타키가_섞이면_같은요청의_시계열키도_저장되지_않는다_부분저장없음")
    void 기술메타_혼합시_전체_거부() {
        // given — 정상 시계열 키 1건 + 기술메타 키 1건
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("0-10", "정상 수정"),
                new MetaUpdateRequest.Item("video.duration_ms", "9999")));

        // when / then — 검증이 upsert 앞에서 끝나 부분 저장이 남지 않는다.
        assertThatThrownBy(() -> service.update(SRC_SN, req, reviewer()))
                .isInstanceOf(CustomException.class);
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("기술메타_접두만_같은_유사키는_거부되지_않는다_과차단_금지")
    void 유사키는_통과() {
        // given — 'video' 로 시작하지만 접두('video.')가 아닌 키는 시계열 메타다.
        LsDataMeta existing = metaWithSn("videoclip-0-10", "설명", 700L);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, "videoclip-0-10"))
                .thenReturn(Optional.of(existing));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(existing));

        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("videoclip-0-10", "수정")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then
        verify(metaRepository).upsertMeta(RAW_SN, "videoclip-0-10", "수정");
    }
}
