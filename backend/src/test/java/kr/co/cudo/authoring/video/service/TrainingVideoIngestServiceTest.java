package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.repository.MngClipEvntLstRepository;
import kr.co.cudo.authoring.video.repository.MngClipMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관제 학습용 영상 픽업 스캔 서비스 단위 테스트.
 *
 * <p>{@link TrainingVideoIngestService#scanAndIngest()} 는 조회(READ)만 담당하고, 클립별 적재는
 * {@link TrainingVideoIngestTx#ingestOne}(REQUIRES_NEW)에 위임한다. 본 테스트는 스캔 조율/부분
 * 실패 격리(한 클립 트랜잭션 롤백이 다른 클립 적재를 막지 않음)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrainingVideoIngestServiceTest {

    @Mock
    private MngClipMasterRepository clipMasterRepository;

    @Mock
    private MngClipEvntLstRepository clipEvntLstRepository;

    @Mock
    private TrainingVideoIngestTx ingestTx;

    private TrainingVideoIngestService service;

    @BeforeEach
    void setUp() {
        service = new TrainingVideoIngestService(clipMasterRepository, clipEvntLstRepository, ingestTx);
        when(clipEvntLstRepository.findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(anyCollection()))
                .thenReturn(List.of());
    }

    private MngClipMaster clip(String evntId, String clipId) {
        MngClipMaster clip = newClip();
        ReflectionTestUtils.setField(clip, "evntId", evntId);
        ReflectionTestUtils.setField(clip, "clipTypeCd", "ORIGINAL");
        ReflectionTestUtils.setField(clip, "clipId", clipId);
        ReflectionTestUtils.setField(clip, "vmsCctvId", "CCTV-" + evntId);
        ReflectionTestUtils.setField(clip, "filePath", "/nas-storage/data/clip/" + clipId + ".mp4");
        ReflectionTestUtils.setField(clip, "jobDmndYn", "Y");
        ReflectionTestUtils.setField(clip, "crtDt", LocalDateTime.of(2026, 6, 1, 10, 0));
        return clip;
    }

    private static MngClipMaster newClip() {
        try {
            var ctor = MngClipMaster.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("관제_작업수요_설정_클립을_픽업해_적재_위임한다")
    void delegatesIngestForTrainingDesignatedClip() {
        // given
        MngClipMaster clip = clip("EVT-1", "CLIP-1");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class))).thenReturn(List.of(clip));
        when(ingestTx.ingestOne(eq(clip), any())).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isEqualTo(1);
        verify(ingestTx).ingestOne(eq(clip), any());
    }

    @Test
    @DisplayName("위임_적재가_skip되면_적재건수에_포함되지_않는다")
    void skippedClipNotCounted() {
        // given — 중복/스킵 클립은 ingestOne 이 false 반환.
        MngClipMaster clip = clip("EVT-DUP", "CLIP-DUP");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class))).thenReturn(List.of(clip));
        when(ingestTx.ingestOne(eq(clip), any())).thenReturn(false);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
    }

    @Test
    @DisplayName("작업수요_미설정_클립은_조회되지_않아_위임하지_않는다")
    void doesNotDelegateForNonTrainingClips() {
        // given — repository 가 'Y' 만 조회하므로 빈 결과.
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class))).thenReturn(List.of());

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
        verify(ingestTx, never()).ingestOne(any(MngClipMaster.class), any());
    }

    @Test
    @DisplayName("한_클립_REQUIRES_NEW_트랜잭션_예외가_다른_클립_적재를_막지_않는다")
    void partialFailureDoesNotBlockOtherClips() {
        // given — 첫 클립 적재 트랜잭션이 JPA 예외로 롤백돼도(REQUIRES_NEW 격리),
        // 둘째 클립 적재 커밋은 영향받지 않아야 한다.
        MngClipMaster bad = clip("EVT-BAD", "CLIP-BAD");
        MngClipMaster good = clip("EVT-GOOD", "CLIP-GOOD");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class))).thenReturn(List.of(bad, good));
        when(ingestTx.ingestOne(eq(bad), any()))
                .thenThrow(new DataIntegrityViolationException("rollback-only marked tx"));
        when(ingestTx.ingestOne(eq(good), any())).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then — 둘째 클립은 적재 성공으로 집계.
        assertThat(ingested).isEqualTo(1);
        verify(ingestTx).ingestOne(eq(good), any());
    }

    @Test
    @DisplayName("여러_클립_중_일부_skip_일부_적재가_정확히_집계된다")
    void mixedResultsCountedCorrectly() {
        // given
        MngClipMaster a = clip("EVT-A", "CLIP-A");
        MngClipMaster b = clip("EVT-B", "CLIP-B");
        MngClipMaster c = clip("EVT-C", "CLIP-C");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class))).thenReturn(List.of(a, b, c));
        when(ingestTx.ingestOne(eq(a), any())).thenReturn(true);
        when(ingestTx.ingestOne(eq(b), any())).thenReturn(false);
        when(ingestTx.ingestOne(eq(c), any())).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isEqualTo(2);
        verify(ingestTx, times(3)).ingestOne(any(MngClipMaster.class), any());
    }

    @Test
    @DisplayName("스캔결과가_null이면_안전하게_0건_처리한다")
    void nullScanResultIsHandledSafely() {
        // given — repository 가 null 을 반환해도 NPE 없이 0 건 처리.
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class))).thenReturn(null);

        // when
        int ingested = service.scanAndIngest();

        // then
        assertThat(ingested).isZero();
        verify(ingestTx, never()).ingestOne(any(MngClipMaster.class), any());
    }

    // ===================== B-ISSUE-04 — 스캔 비용 억제 =====================

    @Test
    @DisplayName("후보조회는_tick당_상한건수의_Pageable로_수행된다")
    void scanQueriesWithTickLimitPageable() {
        // given
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class)))
                .thenReturn(List.of());

        // when
        service.scanAndIngest();

        // then — 상한 없는 전량 조회가 아니라 첫 페이지 + INGEST_SCAN_LIMIT 건 상한으로 조회한다.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(clipMasterRepository).findIngestCandidatesByJobDmndYn(eq("Y"), captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(PageRequest.of(0, TrainingVideoIngestService.INGEST_SCAN_LIMIT));
    }

    @Test
    @DisplayName("상한만큼_조회되면_상한건수만_적재위임하고_잔여분은_다음_tick으로_이월된다")
    void limitReachedProcessesOnlyLimitAndCarriesOverRemainder() {
        // given — 상한(100)만큼 후보가 돌아온 상황(잔여분은 조회 자체에 포함되지 않는다).
        List<MngClipMaster> page = IntStream.range(0, TrainingVideoIngestService.INGEST_SCAN_LIMIT)
                .mapToObj(i -> clip("EVT-" + i, "CLIP-" + i))
                .toList();
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class)))
                .thenReturn(page);
        when(ingestTx.ingestOne(any(MngClipMaster.class), any())).thenReturn(true);

        // when
        int ingested = service.scanAndIngest();

        // then — 한 tick 은 상한 건수만 처리한다(잔여분은 다음 tick 이 이어서 처리).
        assertThat(ingested).isEqualTo(TrainingVideoIngestService.INGEST_SCAN_LIMIT);
        verify(ingestTx, times(TrainingVideoIngestService.INGEST_SCAN_LIMIT))
                .ingestOne(any(MngClipMaster.class), any());
    }

    @Test
    @DisplayName("이벤트리스트는_후보_전체에_대해_IN조회_1회로_배치화된다")
    void eventListIsLoadedWithSingleInQuery() {
        // given — 후보 3건(서로 다른 EVNT_ID). 구 구현은 클립당 1회씩 point lookup 했다.
        MngClipMaster a = clip("EVT-A", "CLIP-A");
        MngClipMaster b = clip("EVT-B", "CLIP-B");
        MngClipMaster c = clip("EVT-C", "CLIP-C");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class)))
                .thenReturn(List.of(a, b, c));
        when(ingestTx.ingestOne(any(MngClipMaster.class), any())).thenReturn(true);

        // when
        service.scanAndIngest();

        // then — IN 조회 1회 + 클립별 개별 조회 0회.
        ArgumentCaptor<java.util.Collection<String>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(clipEvntLstRepository).findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("EVT-A", "EVT-B", "EVT-C");
        verify(clipEvntLstRepository, never()).findFirstByEvntId(any());
    }

    @Test
    @DisplayName("IN조회로_얻은_이벤트리스트가_해당_클립의_적재에_전달된다")
    void resolvedEventListRowIsPassedToIngest() {
        // given — EVT-A 만 이벤트리스트 매칭 존재.
        MngClipMaster a = clip("EVT-A", "CLIP-A");
        MngClipMaster b = clip("EVT-B", "CLIP-B");
        MngClipEvntLst rowA = evntLst("EVT-A", "FIRE");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class)))
                .thenReturn(List.of(a, b));
        when(clipEvntLstRepository.findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(anyCollection()))
                .thenReturn(List.of(rowA));
        when(ingestTx.ingestOne(any(MngClipMaster.class), any())).thenReturn(true);

        // when
        service.scanAndIngest();

        // then — 매칭 클립에는 해당 행이, 미매칭 클립에는 null 이 전달된다(폴백 적재).
        verify(ingestTx).ingestOne(eq(a), eq(rowA));
        verify(ingestTx).ingestOne(eq(b), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("같은_EVNT_ID에_이벤트리스트가_여러행이면_PK오름차순_첫행만_채택한다")
    void duplicateEventListRowsCollapseToFirst() {
        // given — 복합 PK (EVNT_ID, EVNT_TYPE_CD) 상 다행 가능. 정렬 결과 첫 행만 사용(findFirstBy 와 동일 의미).
        MngClipMaster a = clip("EVT-A", "CLIP-A");
        MngClipEvntLst first = evntLst("EVT-A", "FIRE");
        MngClipEvntLst second = evntLst("EVT-A", "INTRUSION");
        when(clipMasterRepository.findIngestCandidatesByJobDmndYn(eq("Y"), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(clipEvntLstRepository.findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(anyCollection()))
                .thenReturn(List.of(first, second));
        when(ingestTx.ingestOne(any(MngClipMaster.class), any())).thenReturn(true);

        // when
        service.scanAndIngest();

        // then
        verify(ingestTx).ingestOne(eq(a), eq(first));
    }

    /** 이벤트리스트 1행 — evntTypeCd 매핑 대상. */
    private MngClipEvntLst evntLst(String evntId, String evntTypeCd) {
        try {
            var ctor = MngClipEvntLst.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            MngClipEvntLst e = ctor.newInstance();
            ReflectionTestUtils.setField(e, "evntId", evntId);
            ReflectionTestUtils.setField(e, "evntTypeCd", evntTypeCd);
            return e;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
