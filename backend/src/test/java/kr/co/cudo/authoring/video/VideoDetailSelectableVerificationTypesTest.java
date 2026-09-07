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
import kr.co.cudo.authoring.video.entity.LsDataIngest;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 영상 상세({@code GET /v1/videos/{rawSn}})의 <b>고를 수 있는 검증 이벤트 유형 목록</b> 검증.
 * [@design API-043] [@design SCREEN-006] [@design AC-1013] [@design UC-019]
 *
 * <p><b>왜 필요한가</b>: 관제가 검증 이벤트 유형을 보내지 않은 영상은 질문 목록
 * ({@code vrfcEvntQuestions})이 <b>언제나 빈 배열</b>이다 — 그 목록은 관제 인입값을 키로 조달되기
 * 때문이다. 그래서 작업자가 유형을 골라도 질문 선택이 끝내 열리지 않았고, 유형별 질문을 주는 다른
 * 창구는 검수자 전용이라 작업자가 부를 수 없다(403). 이 목록의 항목마다 그 유형의 질문을 함께
 * 실어 그 결손을 메운다.
 *
 * <p>수용기준 1~5 + N+1 회피를 덮는다. 프리셋 7종(수용기준 6)은 아래 마지막 절.
 */
@ExtendWith(MockitoExtension.class)
class VideoDetailSelectableVerificationTypesTest {

    private static final long RAW_SN = 91L;

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
                "clip-sel", "cctv-9", "EV01000101", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/clip.mp4",
                LocalDateTime.of(2026, 1, 15, 9, 30), 60);
        ReflectionTestUtils.setField(e, "rawSn", RAW_SN);
        return e;
    }

    private IngestSourceRow ingestRow(String vrfcEvntTypeCd) {
        return new IngestSourceRow() {
            @Override public String getCctvNm() { return "종로구 CCTV-01"; }
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

    /**
     * 유형 카탈로그 행 — 이 엔티티는 <b>등록 통로를 두지 않아</b> 팩토리가 없고 기본 생성자가
     * protected 다(시드가 유일한 적재 경로). 시험에서는 리플렉션으로 세운다.
     */
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

    /* ============ 수용기준 1·2 — 항목마다 그 유형의 질문이 정렬순서 오름차순으로 실린다 ============ */

    @Test
    @DisplayName("★관제_유형_미수신_영상은_고를_수_있는_유형마다_그_유형의_질문목록을_함께_받는다")
    void selectableTypesCarryTheirOwnQuestions() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willReturn(List.of(
                type("fire", "화재", 1),
                type("smoke", "연기", 2)));
        given(vrfcEvntQstnRepository.findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc()).willReturn(List.of(
                question(11L, "fire", 1, "화재가 발생하였는가?"),
                question(12L, "fire", 2, "그 근거는 무엇인가?"),
                question(21L, "smoke", 1, "연기가 발생하였는가?")));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.selectableVrfcEvntTypes())
                .extracting(VideoDetailResponse.SelectableVrfcEvntTypeDto::vrfcEvntTypeCd)
                .containsExactly("fire", "smoke");
        assertThat(response.selectableVrfcEvntTypes())
                .extracting(VideoDetailResponse.SelectableVrfcEvntTypeDto::vrfcEvntTypeNm)
                .containsExactly("화재", "연기");
        // 질문이 <그 유형의 것만> 담긴다 — 다른 유형의 질문이 섞이면 화면이 엉뚱한 문구를 위탁에 싣는다.
        assertThat(response.selectableVrfcEvntTypes().get(0).questions())
                .extracting(VideoDetailResponse.VrfcEvntQuestionDto::vrfcEvntQstnSn)
                .containsExactly(11L, 12L);
        assertThat(response.selectableVrfcEvntTypes().get(1).questions())
                .extracting(VideoDetailResponse.VrfcEvntQuestionDto::vrfcEvntQstnSn)
                .containsExactly(21L);
        // 화면이 보여줄 본문도 함께 온다(식별자만 오면 무엇을 고르는지 알 수 없다).
        assertThat(response.selectableVrfcEvntTypes().get(0).questions().get(0).qstnCn())
                .isEqualTo("화재가 발생하였는가?");
    }

    /**
     * ★ 「그 유형의 첫 번째」가 이 순서로 정해지고 자동 마킹이 그것을 자동 선택한다 — 순서가 흔들리면
     * 화면이 보여준 기본 질문과 위탁에 실리는 질문이 갈린다. 정렬은 저장소 메서드 이름이 갖고 있으며
     * 서비스는 <b>재유도하지 않고</b> 조회 순서를 그대로 옮긴다.
     */
    @Test
    @DisplayName("★유형별_질문은_정렬순서_오름차순이며_서비스가_다시_정렬하지_않는다")
    void questionsKeepRepositorySortOrder() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc())
                .willReturn(List.of(type("violence", "폭력", 4)));
        // 저장소는 (유형코드, 정렬순서) 오름차순으로 준다. PK 순(3,5,8)이 아니라 이 순서여야 한다.
        given(vrfcEvntQstnRepository.findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc()).willReturn(List.of(
                question(5L, "violence", 1, "첫 번째"),
                question(3L, "violence", 2, "두 번째"),
                question(8L, "violence", 3, "세 번째")));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.selectableVrfcEvntTypes().get(0).questions())
                .extracting(VideoDetailResponse.VrfcEvntQuestionDto::vrfcEvntQstnSn)
                .containsExactly(5L, 3L, 8L);
        verify(vrfcEvntQstnRepository).findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc();
    }

    /**
     * ★ 유형 목록의 순서도 저장소(정렬순서 오름차순 + 유형코드 2차 키)에 위임한다 — 서비스가 코드
     * 사전순으로 다시 세우면 화면 표시 순서가 관리 화면과 갈린다.
     */
    @Test
    @DisplayName("★유형_목록의_순서도_저장소_정렬에_위임하고_코드_사전순으로_재정렬하지_않는다")
    void typeOrderIsDelegated() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));
        // 정렬순서대로면 fire → smoke → car_accident 다. 코드 사전순이면 car_accident 가 맨 앞이 된다.
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willReturn(List.of(
                type("fire", "화재", 1),
                type("smoke", "연기", 2),
                type("car_accident", "교통사고", 6)));
        given(vrfcEvntQstnRepository.findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc()).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.selectableVrfcEvntTypes())
                .extracting(VideoDetailResponse.SelectableVrfcEvntTypeDto::vrfcEvntTypeCd)
                .containsExactly("fire", "smoke", "car_accident");
        verify(vrfcEvntTypeRepository).findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc();
    }

    /* ================= 수용기준 3 — 질문이 0건인 유형도 항목 자체는 남는다 ================= */

    /**
     * ★ 질문이 없다고 그 유형을 목록에서 빼면 <b>묘사 축의 {@code event_type} 조차 채울 길이 막힌다</b> —
     * 유형 미수신 영상은 지금 시계열 메타를 하나도 못 받는 상태이고, 유형을 고르는 것 자체가 그 축을
     * 살린다. 질문 칸만 빈 채로 고를 수 있어야 한다.
     */
    @Test
    @DisplayName("★질문이_0건인_유형도_항목은_남고_질문만_빈_배열이다")
    void typeWithoutQuestionsStaysInTheList() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willReturn(List.of(
                type("fire", "화재", 1),
                type("kidnapping", "납치", 7)));
        given(vrfcEvntQstnRepository.findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc()).willReturn(List.of(
                question(11L, "fire", 1, "화재가 발생하였는가?")));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.selectableVrfcEvntTypes()).hasSize(2);
        VideoDetailResponse.SelectableVrfcEvntTypeDto kidnapping =
                response.selectableVrfcEvntTypes().get(1);
        assertThat(kidnapping.vrfcEvntTypeCd()).isEqualTo("kidnapping");
        assertThat(kidnapping.questions()).isNotNull().isEmpty();
    }

    /* ========== 수용기준 4 — 관제 값이 있으면 빈 배열이고 조회 질의가 발생하지 않는다 ========== */

    /**
     * ★★ 노출 판정의 단일 원천이 이 목록이다 — 관제 값이 있는 영상에서 화면은 목록이 비었다는 사실
     * 하나로 유형 선택을 숨긴다. 그리고 그 대부분의 조회에서 <b>추가 질의가 0회</b>여야 한다.
     *
     * <p>결과 단언(빈 배열)만으로는 부족하다: 조회를 하고 나서 버려도 결과는 같다. 그래서
     * <b>호출 자체</b>를 단언한다.
     */
    @Test
    @DisplayName("★★관제_유형이_있으면_목록이_비고_유형카탈로그와_질문_일괄조회를_아예_하지_않는다")
    void controlProvidedTypeSkipsCatalogQueriesEntirely() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("fall"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fall")).willReturn(
                List.of(question(31L, "fall", 1, "쓰러짐이 발생하였는가?")));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.vrfcEvntTypeCd()).isEqualTo("fall");
        assertThat(response.selectableVrfcEvntTypes()).isNotNull().isEmpty();
        // 그 영상의 질문 목록(관제 유형 기준)은 그대로 조달된다 — 이 축은 무변경이다.
        assertThat(response.vrfcEvntQuestions()).hasSize(1);
        verify(vrfcEvntTypeRepository, never()).findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc();
        verify(vrfcEvntQstnRepository, never()).findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc();
    }

    /**
     * 표기 변형(대문자·공백)도 <b>같은 정규화</b>를 거쳐 「관제 값 있음」으로 판정돼야 한다 —
     * 여기서만 다른 규칙을 쓰면 관제가 대문자로 실어 보낸 영상에 유형 선택이 잘못 노출된다.
     */
    @Test
    @DisplayName("관제_유형_표기가_대문자_공백_섞여_와도_목록은_비어_있다")
    void normalizedControlTypeAlsoSuppressesTheList() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("  FALL  "));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fall")).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.selectableVrfcEvntTypes()).isEmpty();
        verify(vrfcEvntTypeRepository, never()).findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc();
    }

    /* ============================== N+1 회피 ============================== */

    /**
     * ★ 유형마다 질문을 따로 조회하면 유형 수만큼 쿼리가 는다. 유형이 몇이든 질문 조회는 <b>1회</b>다.
     *
     * <p>호출 <b>횟수 자체가 계약</b>이라 {@code mockingDetails(...).getInvocations().size()} 로 세지
     * 않는다 — 그 헬퍼는 {@code when(...)} 스터빙 호출까지 함께 세어 숫자가 어긋난다.
     */
    @Test
    @DisplayName("★유형이_여럿이어도_질문_조회는_한_번이다_N플러스1_아님")
    void questionsAreLoadedInASingleQuery() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willReturn(List.of(
                type("fire", "화재", 1),
                type("smoke", "연기", 2),
                type("fall", "쓰러짐", 3),
                type("violence", "폭력", 4)));
        AtomicInteger bulkCalls = new AtomicInteger();
        given(vrfcEvntQstnRepository.findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc())
                .willAnswer(invocation -> {
                    bulkCalls.incrementAndGet();
                    return List.of(
                            question(11L, "fire", 1, "화재가 발생하였는가?"),
                            question(31L, "fall", 1, "쓰러짐이 발생하였는가?"));
                });

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.selectableVrfcEvntTypes()).hasSize(4);
        assertThat(bulkCalls.get()).isEqualTo(1);
        // 유형별 단건 조회로 되돌아가면 이 단언이 깨진다(관제 유형이 없어 그 경로는 호출 0회여야 한다).
        verify(vrfcEvntQstnRepository, never())
                .findByVrfcEvntTypeCdOrderBySortSeqAsc(org.mockito.ArgumentMatchers.anyString());
    }

    /** 카탈로그가 비어 있으면 담을 곳이 없으므로 질문 조회도 하지 않는다. */
    @Test
    @DisplayName("등록된_유형이_하나도_없으면_질문_조회도_하지_않는다")
    void emptyCatalogSkipsQuestionQuery() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));
        given(vrfcEvntTypeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc()).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.selectableVrfcEvntTypes()).isEmpty();
        verify(vrfcEvntQstnRepository, never()).findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc();
    }

    /* ===================== 수용기준 5 — 기존 응답 필드가 하나도 바뀌지 않는다 ===================== */

    @Test
    @DisplayName("★기존_응답_필드는_이름_타입_의미가_전부_그대로다_더하기만_한다")
    void existingFieldsUnchanged() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("fall"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fall")).willReturn(
                List.of(question(31L, "fall", 1, "쓰러짐이 발생하였는가?")));
        given(approvalGate.hasEverApproved(RAW_SN)).willReturn(true);

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.id()).isEqualTo(RAW_SN);
        assertThat(response.rawSn()).isEqualTo(RAW_SN);
        assertThat(response.vmsClipId()).isEqualTo("clip-sel");
        assertThat(response.vmsCctvId()).isEqualTo("cctv-9");
        assertThat(response.evntTypeCd()).isEqualTo("EV01000101");
        assertThat(response.eventTypeCd()).isEqualTo("EV01000101");
        assertThat(response.vrfcEvntTypeCd()).isEqualTo("fall");
        assertThat(response.vrfcEvntQuestions()).hasSize(1);
        assertThat(response.cctvName()).isEqualTo("종로구 CCTV-01");
        assertThat(response.filePath()).isEqualTo("/nas/raw/clip.mp4");
        assertThat(response.durationSec()).isEqualTo(60);
        assertThat(response.fps()).isEqualTo(25.0);
        assertThat(response.frameCount()).isEqualTo(0L);
        assertThat(response.framePreviews()).isEmpty();
        assertThat(response.stages()).isEmpty();
        assertThat(response.reviewSttsCd()).isNull();
        assertThat(response.batchFailureReason()).isNull();
        assertThat(response.deidentHistory()).isEmpty();
        assertThat(response.skippedStages()).isEmpty();
        assertThat(response.clearedStages()).isEmpty();
        assertThat(response.failedStages()).isEmpty();
        assertThat(response.resolution()).isNull();
        assertThat(response.everApproved()).isTrue();
        assertThat(response.derivative()).isFalse();
    }

    /**
     * 기존 {@code from(...)} 오버로드로 만든 응답은 새 필드가 <b>빈 배열</b>이다({@code null} 아님) —
     * 추가만 했으므로 다른 도메인의 조립 경로는 영향받지 않고, 화면도 {@code null} 분기를 하지 않는다.
     */
    @Test
    @DisplayName("기존_from_오버로드는_새_필드를_빈_배열로_채운다_하위호환")
    void legacyBuildersRemainCompatible() {
        VideoDetailResponse legacy = VideoDetailResponse.from(video());

        assertThat(legacy.selectableVrfcEvntTypes()).isNotNull().isEmpty();
    }

    /* ===================== 수용기준 6 — 프리셋에 smoke 가 들어간다 ===================== */

    /**
     * 사업자 개발 서버 {@code GET /events} 실측이 7종을 돌려줬고({@code smoke} 포함) 질문 카탈로그에도
     * 7종이 시드돼 있다. 이 상수는 <b>허용목록이 아니라 프리셋</b>이라 값 하나를 더하는 것이 전부다.
     */
    @Test
    @DisplayName("★검증이벤트유형_프리셋은_7종이며_smoke_를_포함한다")
    void presetContainsSmoke() {
        assertThat(LsDataIngest.VRFC_EVNT_TYPES).hasSize(7);
        assertThat(LsDataIngest.VRFC_EVNT_TYPES).contains("smoke");
        assertThat(LsDataIngest.VRFC_EVNT_TYPES).containsExactlyInAnyOrder(
                "fire", "smoke", "fall", "violence", "flooding", "car_accident", "kidnapping");
    }

    /**
     * ★ 프리셋은 <b>판정에 쓰지 않는다</b> — 목록 밖 값도 형식만 맞으면 통과한다(2026-08-06 반전).
     * 이 단언이 깨지면 폐기된 허용목록 사전 차단이 되살아난 것이다.
     */
    @Test
    @DisplayName("★프리셋_밖의_값도_형식만_맞으면_통과한다_허용목록이_아니다")
    void presetIsNotAnAllowlist() {
        assertThat(LsDataIngest.VRFC_EVNT_TYPES).doesNotContain("earthquake");
        assertThat(LsDataIngest.isVrfcEvntTypeFormatValid("earthquake")).isTrue();
        assertThat(LsDataIngest.isVrfcEvntTypeFormatValid(
                LsDataIngest.normalizeVrfcEvntType(" SMOKE "))).isTrue();
    }
}
