package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntQstn;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntType;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntQstnRepository;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntTypeRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import kr.co.cudo.authoring.video.service.VideoResolutionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 영상 상세({@code GET /v1/videos/{rawSn}})의 <b>검증 이벤트 유형 전체 목록</b>({@code allVrfcEvntTypes}) 검증.
 * [@design API-043]
 *
 * <p><b>왜 필요한가</b>: 이벤트 어노테이션 창이 이벤트 분류를 「이름 + 코드」로 보여줘야 하는데, 그
 * 분류는 마킹 선택값·사람 수정값이라 {@code vrfcEvntTypeCd}(관제 인입 값)로 이름을 정할 수 없고,
 * 유형 이름을 주는 다른 조회 경로는 검수자 전용이다.
 *
 * <p><b>고정하는 계약</b>
 * <ol>
 *   <li>관제 수신 여부와 무관하게 늘 <b>같은 전체 목록</b>이 온다.</li>
 *   <li>관제 유형이 있는 영상에서 {@code selectableVrfcEvntTypes} 는 <b>여전히 빈 배열</b>이다 — 새
 *       필드가 그 계약을 대신하거나 흔들지 않는다.</li>
 *   <li>정렬은 저장소 메서드에 위임한다 — 서비스가 코드 사전순 등으로 다시 정렬하지 않는다.</li>
 *   <li>마스터가 비면 {@code null} 이 아니라 빈 배열이다.</li>
 *   <li>카탈로그 조회는 영상 1건당 <b>정확히 1회</b>다(두 목록이 공유한다).</li>
 *   <li>질문 목록을 담지 않는다.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VideoDetailAllVerificationTypesTest {

    private static final long RAW_SN = 93L;

    @Mock private VideoRepository videoRepository;
    @Mock private IngestSourceRepository ingestSourceRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;
    @Mock private LsTaskAssignmentRepository taskAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock private kr.co.cudo.authoring.review.repository.IssueRepository issueRepository;
    @Mock private kr.co.cudo.authoring.batch.status.BatchStatusService batchStatusService;
    @Mock private kr.co.cudo.authoring.eventtype.service.EventTypeService eventTypeService;
    @Mock private VideoFpsResolver fpsResolver;
    @Mock private VideoResolutionResolver resolutionResolver;
    @Mock private kr.co.cudo.authoring.assignment.service.ReviewApprovalGate approvalGate;
    @Mock private kr.co.cudo.authoring.batch.status.BatchBundleFailureGate bundleFailureGate;
    @Mock private LsVrfcEvntQstnRepository vrfcEvntQstnRepository;
    @Mock private LsVrfcEvntTypeRepository vrfcEvntTypeRepository;

    @InjectMocks private VideoQueryService videoQueryService;

    @BeforeEach
    void wireRealUserNameResolver() {
        ReflectionTestUtils.setField(videoQueryService, "userNameResolver",
                new UserNameResolver(userRepository));
    }

    // ------------------------------------------------------------------ fixtures

    private LsDataRaw video() {
        LsDataRaw e = LsDataRaw.createFromIngest(
                "clip-all", "cctv-3", "EV01000101", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/clip-all.mp4",
                LocalDateTime.of(2026, 1, 15, 9, 30), 60);
        ReflectionTestUtils.setField(e, "rawSn", RAW_SN);
        return e;
    }

    private IngestSourceRow ingestRow(String vrfcEvntTypeCd) {
        return new IngestSourceRow() {
            @Override public String getCctvNm() { return "종로구 CCTV-03"; }
            @Override public String getLclgvNm() { return "서울 종로구"; }
            @Override public String getEvntClsfCd() { return null; }
            @Override public String getEvntCtgryCd() { return null; }
            @Override public String getEvntId() { return null; }
            @Override public String getVrfcEvntTypeCd() { return vrfcEvntTypeCd; }
            @Override public String getSrcAnonyInclYn() { return null; }
            @Override public String getSrcPsdoInclYn() { return null; }
            @Override public String getSrcPrvcInclYn() { return null; }
        };
    }

    /** 유형 카탈로그 행 — 엔티티에 등록 통로가 없어 리플렉션으로 세운다. */
    private LsVrfcEvntType type(String cd, String nm, int sortSeq) {
        LsVrfcEvntType t = org.springframework.beans.BeanUtils.instantiateClass(LsVrfcEvntType.class);
        ReflectionTestUtils.setField(t, "vrfcEvntTypeCd", cd);
        ReflectionTestUtils.setField(t, "vrfcEvntTypeNm", nm);
        ReflectionTestUtils.setField(t, "sortSeq", sortSeq);
        return t;
    }

    private LsVrfcEvntQstn question(long qstnSn, String typeCd, int sortSeq, String text) {
        LsVrfcEvntQstn q = LsVrfcEvntQstn.create(typeCd, sortSeq, text, "seed");
        ReflectionTestUtils.setField(q, "vrfcEvntQstnSn", qstnSn);
        return q;
    }

    /**
     * 저장소 정렬 결과를 흉내 낸 카탈로그 — 정렬순서 기준이라 <b>코드 사전순과 다르다</b>
     * (fire·smoke·fall). 서비스가 코드로 재정렬하면 순서 단언이 깨지도록 일부러 고른 조합이다.
     */
    private List<LsVrfcEvntType> catalog() {
        return List.of(
                type("fire", "화재", 1),
                type("smoke", "연기", 2),
                type("fall", "쓰러짐", 3));
    }

    private void stubDetailBasics() {
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(video()));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(0L);
        given(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).willReturn(List.of());
        given(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).willReturn(List.of());
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(25.0);
        given(deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(RAW_SN)).willReturn(List.of());
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(batchStatusService.failureReasonFor(RAW_SN)).willReturn(null);
        given(batchStatusService.manuallySkippedBundles(RAW_SN)).willReturn(List.of());
        given(batchStatusService.clearedBundlesNeedingAction(RAW_SN)).willReturn(List.of());
        given(bundleFailureGate.failedBundles(RAW_SN)).willReturn(List.of());
    }

    /* ============================ 관제 유형 미수신 영상 ============================ */

    @Test
    @DisplayName("★관제_유형_미수신_영상은_검증이벤트유형_전체를_저장소_정렬대로_코드와_이름으로_받는다")
    void unreceivedVideoGetsWholeCatalogInRepositoryOrder() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willReturn(catalog());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.allVrfcEvntTypes())
                .extracting(VideoDetailResponse.VrfcEvntTypeDto::vrfcEvntTypeCd,
                        VideoDetailResponse.VrfcEvntTypeDto::vrfcEvntTypeNm)
                .containsExactly(
                        tuple("fire", "화재"),
                        tuple("smoke", "연기"),
                        tuple("fall", "쓰러짐"));
    }

    /* ============================ 관제 유형 수신 영상 ============================ */

    /**
     * ★ 새 필드가 {@code selectableVrfcEvntTypes} 의 계약(관제 값이 있으면 빈 배열 — 비었는지가 유형
     * 선택 노출을 가른다)을 흔들지 않는지 한 응답 안에서 함께 고정한다.
     */
    @Test
    @DisplayName("★★관제_유형이_있는_영상도_전체_목록을_받고_고를_수_있는_목록은_여전히_빈_배열이다")
    void receivedVideoStillGetsWholeCatalogButSelectableStaysEmpty() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("fall"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fall")).willReturn(
                List.of(question(31L, "fall", 1, "쓰러짐이 발생하였는가?")));
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willReturn(catalog());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.vrfcEvntTypeCd()).isEqualTo("fall");
        assertThat(response.allVrfcEvntTypes())
                .extracting(VideoDetailResponse.VrfcEvntTypeDto::vrfcEvntTypeCd)
                .containsExactly("fire", "smoke", "fall");
        assertThat(response.selectableVrfcEvntTypes()).isNotNull().isEmpty();
        // 그 영상 유형의 질문 목록 축은 무변경이다.
        assertThat(response.vrfcEvntQuestions()).hasSize(1);
        // 선택을 노출하지 않는 영상이라 유형별 질문 일괄조회는 여전히 하지 않는다.
        verify(vrfcEvntQstnRepository, never()).findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc();
    }

    /* ============================ 빈 마스터 ============================ */

    @Test
    @DisplayName("등록된_검증이벤트유형이_없으면_전체_목록은_null이_아니라_빈_배열이다")
    void emptyCatalogYieldsEmptyArray() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("fall"));
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.allVrfcEvntTypes()).isNotNull().isEmpty();
    }

    /* ============================ 조회 횟수 ============================ */

    /**
     * ★ 카탈로그는 영상 1건당 <b>정확히 1회</b> 읽힌다 — 전체 목록과 고를 수 있는 목록이 같은 조회
     * 결과를 공유한다. 호출 횟수 자체가 계약이라 {@code mockingDetails} 대신 Answer 카운터로 센다
     * (그 헬퍼는 스터빙 호출까지 함께 센다).
     */
    @Test
    @DisplayName("★관제_유형_미수신_영상에서도_유형_카탈로그_조회는_영상_1건당_한_번이다")
    void catalogIsReadOnceEvenWhenBothListsNeedIt() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));
        AtomicInteger catalogCalls = new AtomicInteger();
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willAnswer(invocation -> {
            catalogCalls.incrementAndGet();
            return catalog();
        });
        given(vrfcEvntQstnRepository.findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc()).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.allVrfcEvntTypes()).hasSize(3);
        assertThat(response.selectableVrfcEvntTypes()).hasSize(3);
        assertThat(catalogCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("관제_유형이_있는_영상에서도_유형_카탈로그_조회는_한_번이다")
    void catalogIsReadOnceForReceivedVideo() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("fire"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fire")).willReturn(List.of());
        AtomicInteger catalogCalls = new AtomicInteger();
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willAnswer(invocation -> {
            catalogCalls.incrementAndGet();
            return catalog();
        });

        videoQueryService.getOne(RAW_SN);

        assertThat(catalogCalls.get()).isEqualTo(1);
    }

    /* ============================ 모양·하위호환 ============================ */

    /** 원소는 코드·이름 두 필드뿐이다 — 질문 목록을 싣지 않는다. */
    @Test
    @DisplayName("전체_목록_원소는_유형코드와_유형이름만_담고_질문목록은_담지_않는다")
    void elementCarriesOnlyCodeAndName() {
        assertThat(Arrays.stream(VideoDetailResponse.VrfcEvntTypeDto.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("vrfcEvntTypeCd", "vrfcEvntTypeNm");
    }

    @Test
    @DisplayName("기존_from_오버로드는_전체_목록을_빈_배열로_채운다_하위호환")
    void legacyBuildersFillEmptyArray() {
        assertThat(VideoDetailResponse.from(video()).allVrfcEvntTypes()).isNotNull().isEmpty();
        assertThat(VideoDetailResponse.from(video(), null, null, 0L, List.of(), null, List.of(), null,
                List.of(), false, null, List.of(), List.of(), List.of(), null, List.of(), null, List.of())
                .allVrfcEvntTypes()).isNotNull().isEmpty();
        assertThat(VideoDetailResponse.from(video(), null, null, 0L, List.of(), null, List.of(), null,
                List.of(), false, null, List.of(), List.of(), List.of(), null, List.of(), null, List.of(), null)
                .allVrfcEvntTypes()).isNotNull().isEmpty();
    }
}
