package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * R5 N+1 방지 검증 — 배정 정보 enrich 가 페이지 크기와 무관하게 batch(IN) 1회로 조회됨을 보장.
 *
 * <p>영상 N건이 모두 배정되어 있어도 LsTaskAssignmentRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc
 * 는 1회, 배정자 이름 조회(UserRepository.findByUserNoIn)도 1회만 호출되어야 한다 (영상당 개별 조회 금지).
 */
@ExtendWith(MockitoExtension.class)
class VideoListAssignmentBatchLookupTest {

    @Mock private VideoRepository videoRepository;
    @Mock private IngestSourceRepository ingestSourceRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;
    @Mock private LsTaskAssignmentRepository taskAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock private kr.co.cudo.authoring.batch.status.BatchStatusService batchStatusService;

    @Mock private kr.co.cudo.authoring.eventtype.service.EventTypeService eventTypeService;

    @InjectMocks private VideoQueryService videoQueryService;

    private LsDataRaw video(long rawSn) {
        LsDataRaw e = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/c" + rawSn + ".mp4",
                LocalDateTime.now(), 30);
        org.springframework.test.util.ReflectionTestUtils.setField(e, "rawSn", rawSn);
        return e;
    }

    private LsTaskAssignment assignment(long assignmentId, long rawDataId, long userNo) {
        LsTaskAssignment a = LsTaskAssignment.createLabeler(rawDataId, userNo, 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(a, "assignmentId", assignmentId);
        return a;
    }

    private LsDataRaw videoWithCctv(long rawSn, String cctvId) {
        LsDataRaw e = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, cctvId, "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/c" + rawSn + ".mp4",
                LocalDateTime.now(), 30);
        org.springframework.test.util.ReflectionTestUtils.setField(e, "rawSn", rawSn);
        return e;
    }

    private MngAcctUser user(long userNo, String name) {
        MngAcctUser u = org.mockito.Mockito.mock(MngAcctUser.class);
        given(u.getUserNo()).willReturn(userNo);
        given(u.getUserNm()).willReturn(name);
        return u;
    }

    /** {@code VideoRepository#findCctvNamesByRawSns} 반환 행 — {@code [rawSn, cctvNm, vmsCctvId]}. */
    private Object[] cctvRow(long rawSn, String cctvId, String name) {
        return new Object[]{rawSn, name, cctvId};
    }

    @Test
    @DisplayName("영상목록_조회는_frameCount와_cctv명_조회에서_N+1이_발생하지_않는다")
    void noNPlusOneForFrameCountAndCctv() {
        // given: 영상 5건 — 각기 다른 CCTV
        int size = 5;
        List<LsDataRaw> rows = IntStream.rangeClosed(1, size)
                .mapToObj(i -> videoWithCctv(i, "CCTV-00" + i)).toList();
        Pageable pageable = PageRequest.of(0, size);
        Page<LsDataRaw> page = new PageImpl<>(rows, pageable, size);
        given(videoRepository.searchOriginals(any(), any(), any(), any(), anyInt(), anyCollection(),
                any(), any(), any(Pageable.class))).willReturn(page);

        // frameCount batch: rawSn i → count i*10 (리스트 선생성 — 중첩 stubbing 회피)
        List<Object[]> frameCounts = IntStream.rangeClosed(1, size)
                .mapToObj(i -> new Object[]{(long) i, (long) (i * 10)})
                .toList();
        given(srcRepository.countByRawSnsGrouped(anyCollection())).willReturn(frameCounts);
        // cctv batch: 관제 인입 평면값(LS_DATA_INGEST.CCTV_NM)을 영상 단위로 batch 조회한다(V167)
        List<Object[]> cctvs = IntStream.rangeClosed(1, size)
                .mapToObj(i -> cctvRow(i, "CCTV-00" + i, "CCTV명" + i))
                .toList();
        given(videoRepository.findCctvNamesByRawSns(anyCollection())).willReturn(cctvs);

        given(videoRepository.findLatestExportsByRawSns(anyCollection())).willReturn(List.of());
        given(rawDataStatusRepository.findByRawDataIdIn(anyCollection())).willReturn(List.of());
        given(taskAssignmentRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(anyString(), anyCollection()))
                .willReturn(List.of());

        // when
        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        // then: frameCount/cctv 모두 batch 1회, 건별 조회 금지
        verify(srcRepository, times(1)).countByRawSnsGrouped(anyCollection());
        verify(srcRepository, never()).countByRawSn(anyLong());
        verify(videoRepository, times(1)).findCctvNamesByRawSns(anyCollection());
        verify(ingestSourceRepository, never()).findSourceMeta(anyLong());

        // 값 보존: frameCount 매핑, cctvName 매핑
        assertThat(result.getContent().get(0).frameCount()).isEqualTo(10L);
        assertThat(result.getContent().get(0).cctvName()).isEqualTo("CCTV명1");
        assertThat(result.getContent().get(4).frameCount()).isEqualTo(50L);
        assertThat(result.getContent().get(4).cctvName()).isEqualTo("CCTV명5");
    }

    @Test
    @DisplayName("영상목록_조회는_배정정보_포함시에도_N+1이_발생하지_않는다")
    void noNPlusOneForAssignmentEnrich() {
        // given: 영상 5건 모두 LABELER 배정됨 (서로 다른 작업자)
        int size = 5;
        List<LsDataRaw> rows = IntStream.rangeClosed(1, size).mapToObj(i -> video(i)).toList();
        Pageable pageable = PageRequest.of(0, size);
        Page<LsDataRaw> page = new PageImpl<>(rows, pageable, size);
        given(videoRepository.searchOriginals(eq("COMPLETED"), any(), any(), any(), anyInt(), anyCollection(),
                any(), any(), any(Pageable.class))).willReturn(page);

        List<LsTaskAssignment> assignments = IntStream.rangeClosed(1, size)
                .mapToObj(i -> assignment(1000 + i, i, 100 + i)).toList();
        given(taskAssignmentRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(
                eq(LsTaskAssignment.TASK_LABELER), anyCollection())).willReturn(assignments);

        List<MngAcctUser> users = IntStream.rangeClosed(1, size)
                .mapToObj(i -> user(100 + i, "작업자" + (100 + i))).toList();
        given(userRepository.findByUserNoIn(anyCollection())).willReturn(users);

        // export / status 는 빈 결과
        given(videoRepository.findLatestExportsByRawSns(anyCollection())).willReturn(List.of());
        given(rawDataStatusRepository.findByRawDataIdIn(anyCollection())).willReturn(List.of());

        // when
        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, "COMPLETED", null);

        // then: 배정 조회 1회 + 이름 조회 1회 (영상당 개별 조회 금지)
        verify(taskAssignmentRepository, times(1))
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(anyString(), anyCollection());
        verify(userRepository, times(1)).findByUserNoIn(anyCollection());

        // 각 영상에 현재 배정자가 반영되었는지 (rawSn=i → userNo=100+i)
        assertThat(result.getContent()).hasSize(size);
        VideoSummaryResponse first = result.getContent().get(0);
        assertThat(first.workerId()).isEqualTo(101L);
        assertThat(first.workerName()).isEqualTo("작업자101");
        assertThat(first.assignStatus()).isEqualTo("ASSIGNED");
        assertThat(first.assignmentId()).isEqualTo(1001L);
    }
}
