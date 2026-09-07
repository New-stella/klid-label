package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntQstn;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntQstnRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.video.controller.VideoController;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 영상 상세({@code GET /v1/videos/{rawSn}})의 <b>검증 이벤트 유형 + 질문 목록</b> 노출 검증.
 * [@design API-043] [@design ERD-033] [@design UC-019] [@design AC-028]
 *
 * <p><b>왜 이 응답에 싣나</b>: 마킹 화면이 작업자에게 질문을 보여주고 고른 값을 마킹 등록 요청에
 * 실어야 하는데, 질문 카탈로그를 관리하는 조회 경로는 <b>검수자 전용</b>이라 작업자에게 403 이다.
 * 마킹 화면이 이미 이 응답을 소비하므로 경로를 새로 만들지 않고 여기에 싣는다. 서버는 그 선택값을
 * 받아 해석·보관하는 데까지 이미 끝났고, 화면이 고를 목록을 얻을 통로만 없었다.
 *
 * <p>수용기준 1~5 + 정렬 결정성을 덮는다.
 */
@ExtendWith(MockitoExtension.class)
class VideoDetailVerificationQuestionsTest {

    private static final long RAW_SN = 77L;

    @Mock private VideoRepository videoRepository;
    @Mock private IngestSourceRepository ingestSourceRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;
    @Mock private LsTaskAssignmentRepository taskAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LsDeidentProcLogRepository deidentProcLogRepository;
    // 프레임 이슈 점 조달 — 이 시험들의 축은 아니지만 상세 조립이 영상당 1회 부른다(미스텁 시 빈 목록).
    @Mock private kr.co.cudo.authoring.review.repository.IssueRepository issueRepository;
    @Mock private kr.co.cudo.authoring.batch.status.BatchStatusService batchStatusService;
    @Mock private kr.co.cudo.authoring.eventtype.service.EventTypeService eventTypeService;
    @Mock private VideoFpsResolver fpsResolver;
    // 해상도 표시값 조달(video.resolution) — 미상이면 null 이라 폴백 스텁이 필요 없다.
    @Mock private VideoResolutionResolver resolutionResolver;
    @Mock private kr.co.cudo.authoring.assignment.service.ReviewApprovalGate approvalGate;
    @Mock private kr.co.cudo.authoring.batch.status.BatchBundleFailureGate bundleFailureGate;
    @Mock private LsVrfcEvntQstnRepository vrfcEvntQstnRepository;

    /**
     * 고를 수 있는 검증 이벤트 유형 카탈로그 — 관제가 유형을 보내지 않은 영상에서만 읽힌다.
     * {@code @InjectMocks} 는 목이 없는 타입에 null 을 넣으므로 여기서 채운다(미스텁 시 빈 목록).
     */
    @Mock private kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntTypeRepository vrfcEvntTypeRepository;

    @InjectMocks private VideoQueryService videoQueryService;

    @BeforeEach
    void wireRealUserNameResolver() {
        ReflectionTestUtils.setField(videoQueryService, "userNameResolver",
                new UserNameResolver(userRepository));
    }

    // ------------------------------------------------------------------ fixtures

    private LsDataRaw video() {
        LsDataRaw e = LsDataRaw.createFromIngest(
                "clip-q", "cctv-9", "EV01000101", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/clip.mp4",
                LocalDateTime.of(2026, 1, 15, 9, 30), 60);
        ReflectionTestUtils.setField(e, "rawSn", RAW_SN);
        return e;
    }

    /** 인입 평면값 프로젝션 — 검증이벤트유형만 의미 있고 나머지는 이 테스트의 관심사가 아니다. */
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

    /* ================= 수용기준 1 — 유형 + 질문 목록이 정렬순서 오름차순으로 나온다 ================= */

    @Test
    @DisplayName("★검증이벤트유형과_그_유형의_질문목록이_정렬순서_오름차순으로_실린다")
    void exposesTypeAndQuestionsInSortOrder() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("fall"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fall")).willReturn(List.of(
                question(31L, "fall", 1, "영상에서 쓰러짐 이벤트가 발생하였는가?"),
                question(32L, "fall", 2, "그렇게 판단한 근거는 무엇인가?")));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.vrfcEvntTypeCd()).isEqualTo("fall");
        assertThat(response.vrfcEvntQuestions())
                .extracting(VideoDetailResponse.VrfcEvntQuestionDto::vrfcEvntQstnSn)
                .containsExactly(31L, 32L);
        assertThat(response.vrfcEvntQuestions())
                .extracting(VideoDetailResponse.VrfcEvntQuestionDto::qstnCn)
                .containsExactly("영상에서 쓰러짐 이벤트가 발생하였는가?", "그렇게 판단한 근거는 무엇인가?");
    }

    /**
     * ★ 화면이 고른 값을 마킹 등록 요청에 실어야 하므로 <b>식별자와 본문</b>이 함께 있어야 한다 —
     * 본문만 내리면 화면은 무엇을 골랐는지 서버에 말할 수 없다.
     */
    @Test
    @DisplayName("★질문_한_건은_식별자와_본문을_모두_갖는다")
    void questionCarriesIdAndText() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("fire"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fire")).willReturn(
                List.of(question(9L, "fire", 1, "화재가 발생하였는가?")));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.vrfcEvntQuestions()).hasSize(1);
        VideoDetailResponse.VrfcEvntQuestionDto item = response.vrfcEvntQuestions().get(0);
        assertThat(item.vrfcEvntQstnSn()).isEqualTo(9L);
        assertThat(item.qstnCn()).isEqualTo("화재가 발생하였는가?");
    }

    /* ================= 수용기준 2 — 유형은 있는데 질문이 0건이면 빈 목록(오류 아님) ================= */

    @Test
    @DisplayName("유형은_있는데_등록된_질문이_0건이면_빈_목록이며_오류가_아니다")
    void emptyQuestionsIsNotAnError() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("flooding"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("flooding"))
                .willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.vrfcEvntTypeCd()).isEqualTo("flooding");
        assertThat(response.vrfcEvntQuestions()).isEmpty();
    }

    /**
     * ★ 이 카탈로그는 <b>허용목록이 아니다</b> — 확정 정책상 목록 밖 유형도 위탁은 그대로 나가고
     * 질문 칸만 빈다. 조회를 막거나 예외를 던지면 그 정책이 이 통로에서만 뒤집힌다.
     */
    @Test
    @DisplayName("★카탈로그에_없는_유형도_조회는_정상이며_질문_칸만_빈다")
    void unknownTypeStillResolvesNormally() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("smoke"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("smoke")).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.vrfcEvntTypeCd()).isEqualTo("smoke");
        assertThat(response.vrfcEvntQuestions()).isEmpty();
    }

    /* ============ 수용기준 3 — 인입 행이 없어 유형을 알 수 없으면 빈 목록(오류 아님) ============ */

    @Test
    @DisplayName("인입_행이_없는_영상은_유형이_null이고_질문은_빈_목록이다_오류_아님")
    void noIngestRowYieldsEmpty() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(null);

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.vrfcEvntTypeCd()).isNull();
        assertThat(response.vrfcEvntQuestions()).isEmpty();
        // 유형이 없으면 소유 키가 없다 — 조회 자체를 하지 않는다(전 유형 카탈로그로 폴백하지 않는다).
        verify(vrfcEvntQstnRepository, never()).findByVrfcEvntTypeCdOrderBySortSeqAsc(anyString());
    }

    @Test
    @DisplayName("관제가_검증이벤트유형을_보내지_않았으면_유형이_null이고_질문은_빈_목록이다")
    void missingTypeYieldsEmpty() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow(null));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.vrfcEvntTypeCd()).isNull();
        assertThat(response.vrfcEvntQuestions()).isEmpty();
        verify(vrfcEvntQstnRepository, never()).findByVrfcEvntTypeCdOrderBySortSeqAsc(anyString());
    }

    /* ===================== 수용기준 4 — 기존 응답 필드가 하나도 바뀌지 않는다 ===================== */

    @Test
    @DisplayName("★기존_응답_필드는_하나도_바뀌지_않는다_더하기만_한다")
    void existingFieldsUnchanged() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("fall"));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fall")).willReturn(
                List.of(question(31L, "fall", 1, "쓰러짐이 발생하였는가?")));
        given(approvalGate.hasEverApproved(RAW_SN)).willReturn(true);

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.id()).isEqualTo(RAW_SN);
        assertThat(response.rawSn()).isEqualTo(RAW_SN);
        assertThat(response.vmsClipId()).isEqualTo("clip-q");
        assertThat(response.vmsCctvId()).isEqualTo("cctv-9");
        // ★ 관제 이벤트유형(evntTypeCd)과 검증이벤트유형(vrfcEvntTypeCd)은 축이 다른 값이다.
        //   한쪽이 다른 쪽을 덮어쓰거나 유도하면 안 된다.
        assertThat(response.evntTypeCd()).isEqualTo("EV01000101");
        assertThat(response.eventTypeCd()).isEqualTo("EV01000101");
        assertThat(response.vrfcEvntTypeCd()).isEqualTo("fall");
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
        assertThat(response.everApproved()).isTrue();
        assertThat(response.derivative()).isFalse();
    }

    /**
     * 기존 {@code from(...)} 오버로드로 만든 응답은 새 필드가 null + 빈 배열이다 — 추가만 했으므로
     * 기존 소비자(다른 도메인의 조립 경로)는 영향받지 않는다.
     */
    @Test
    @DisplayName("기존_from_오버로드는_새_필드를_null과_빈_배열로_채운다_하위호환")
    void legacyBuildersRemainCompatible() {
        VideoDetailResponse legacy = VideoDetailResponse.from(video());

        assertThat(legacy.vrfcEvntTypeCd()).isNull();
        assertThat(legacy.vrfcEvntQuestions()).isEmpty();
    }

    /* ========================= 수용기준 5 — 권한 축이 기존과 같다 ========================= */

    @Test
    @DisplayName("★영상_상세의_권한_축은_기존_그대로다_새_권한을_만들지_않는다")
    void permissionAxisUnchanged() throws NoSuchMethodException {
        PreAuthorize preAuthorize = VideoController.class
                .getMethod("getOne", Long.class, kr.co.cudo.authoring.common.security.TokenClaims.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('REVIEWER','WORKER')");
    }

    /* ============================== 정렬 결정성 ============================== */

    /**
     * ★「첫 번째 질문」의 결정성이 이 정렬에 걸려 있다 — 마킹을 거치지 않는 경로가 채우는 기본값이
     * 조회마다 달라지면 안 된다. 서비스는 <b>정렬을 재유도하지 않고</b> 정렬 규칙을 이름에 담은
     * 저장소 메서드를 부른 뒤 그 순서를 그대로 옮긴다(사본을 두면 두 번째 진실원이 된다).
     */
    @Test
    @DisplayName("★정렬은_저장소의_정렬순서_오름차순_조회에_위임하고_서비스가_재유도하지_않는다")
    void sortingIsDelegatedNotRederived() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("violence"));
        // 저장소가 돌려준 순서(정렬순서 1,2,3)를 그대로 보존하는지 본다.
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("violence")).willReturn(List.of(
                question(5L, "violence", 1, "첫 번째"),
                question(3L, "violence", 2, "두 번째"),
                question(8L, "violence", 3, "세 번째")));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // PK 순(3,5,8)도 삽입 순도 아닌 <정렬순서> 순이다.
        assertThat(response.vrfcEvntQuestions())
                .extracting(VideoDetailResponse.VrfcEvntQuestionDto::vrfcEvntQstnSn)
                .containsExactly(5L, 3L, 8L);
        verify(vrfcEvntQstnRepository).findByVrfcEvntTypeCdOrderBySortSeqAsc("violence");
    }

    /**
     * ★ 유형 코드 표기 정규화는 인입이 쓰는 <b>같은 함수</b>를 재사용한다 — 규칙을 복제하면 관제가
     * 대문자로 실어 보낸 값이 여기서만 조달에 실패해 질문 칸이 조용히 빈다.
     */
    @Test
    @DisplayName("★유형_표기가_대문자_공백_섞여_와도_같은_정규화로_조달된다")
    void typeCodeIsNormalizedWithTheSharedRule() {
        stubDetailBasics();
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(ingestRow("  FALL  "));
        given(vrfcEvntQstnRepository.findByVrfcEvntTypeCdOrderBySortSeqAsc("fall")).willReturn(
                List.of(question(31L, "fall", 1, "쓰러짐이 발생하였는가?")));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // 응답에 실린 코드와 목록을 찾은 키가 같아야 한다 — 어긋나면 "이 유형의 질문"이라는 근거가 깨진다.
        assertThat(response.vrfcEvntTypeCd()).isEqualTo("fall");
        assertThat(response.vrfcEvntQuestions()).hasSize(1);
        verify(vrfcEvntQstnRepository).findByVrfcEvntTypeCdOrderBySortSeqAsc("fall");
    }
}
