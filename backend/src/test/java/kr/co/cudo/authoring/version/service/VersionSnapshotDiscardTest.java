package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D5 — 검수 승인 버전 스냅샷이 <b>그 프레임의 폐기여부</b>를 함께 담는지 고정한다.
 *
 * <h3>왜 형제 프레임의 폐기여부는 담지 않는가 (Critical)</h3>
 * 스냅샷 payload 는 SHA-256 해시({@code VERSION_HASH})의 입력이고 그 해시가 멱등 판정 축이다.
 * 형제 폐기여부까지 담으면 <b>프레임 5를 폐기하는 순간 같은 영상 모든 프레임의 해시가 흔들려</b>
 * 라벨이 하나도 안 바뀐 프레임까지 새 스냅샷이 적층된다. 폐기는 프레임 축이고 스냅샷은 프레임 단위
 * 행이므로 <b>자기 프레임의 값만</b> 담는다.
 *
 * <p>형식 전환의 과도기 호환(필드 부재 = 폐기 아님)은 {@link SnapshotDiscardPolicy} 가 담당한다.
 *
 * @design D5
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class VersionSnapshotDiscardTest {

    private static final Long RAW_SN = 9L;
    private static final Long SRC_A = 51L;
    private static final Long SRC_B = 52L;

    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private WorkLockService workLockService;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository labelRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ReviewApprovalGate approvalGate;
    @Mock private LsDataLblAiInfoRepository aiInfoRepository;
    @Mock private LsDataLblAttrValRepository attrValRepository;
    @Mock private LsDataLblHstryRepository labelHistoryRepository;
    @Mock private UserNameResolver userNameResolver;

    private VersionService versionService;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        versionService = new VersionService(
                labelVersionRepository, accessGuard, videoRepository, workLockService,
                srcRepository, labelRepository, new ObjectMapper(), eventPublisher,
                approvalGate, aiInfoRepository, attrValRepository, labelHistoryRepository,
                userNameResolver);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("승인_스냅샷은_그_프레임의_폐기여부를_담는다")
    void 승인_스냅샷은_그_프레임의_폐기여부를_담는다() {
        LsDataSrc discarded = frame(SRC_A, 0);
        discarded.discard();
        stubCommit(List.of(discarded));

        versionService.commitApproved(RAW_SN, reviewer);

        assertThat(savedPayload()).contains("\"dscdYn\":\"Y\"");
    }

    @Test
    @DisplayName("폐기되지_않은_프레임의_스냅샷은_폐기여부_N_을_담는다")
    void 폐기되지_않은_프레임의_스냅샷은_폐기여부_N_을_담는다() {
        stubCommit(List.of(frame(SRC_A, 0)));

        versionService.commitApproved(RAW_SN, reviewer);

        assertThat(savedPayload()).contains("\"dscdYn\":\"N\"");
    }

    @Test
    @DisplayName("형제_프레임의_폐기여부는_스냅샷에_담지_않는다")
    void 형제_프레임의_폐기여부는_스냅샷에_담지_않는다() {
        LsDataSrc current = frame(SRC_A, 0);
        LsDataSrc sibling = frame(SRC_B, 1);
        sibling.discard();
        stubCommit(List.of(current, sibling));

        versionService.commitApproved(RAW_SN, reviewer);

        // siblings 배열에는 폐기 축이 없어야 한다 — payload 전체에서 dscdYn 은 루트 1회뿐.
        String payload = savedPayload();
        assertThat(payload.split("dscdYn", -1)).hasSize(2);
    }

    // ---------- 스텁 ----------

    private void stubCommit(List<LsDataSrc> frames) {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(frames);
        // 첫 프레임에만 라벨이 있다 — 라벨 0건 프레임은 스냅샷을 만들지 않는 기존 규약을 그대로 둔다.
        when(labelRepository.findBySrcSnIn(anyList())).thenReturn(List.of(label(frames.get(0).getSrcSn())));
        when(labelVersionRepository.findActiveForUpdate(eq(RAW_SN), any(), any())).thenReturn(List.of());
        when(srcRepository.lockAndReadLabelVersion(any())).thenReturn(Optional.of(1L));
        when(labelVersionRepository.save(any(LsLabelVersion.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private String savedPayload() {
        ArgumentCaptor<LsLabelVersion> saved = ArgumentCaptor.forClass(LsLabelVersion.class);
        verify(labelVersionRepository).save(saved.capture());
        return saved.getValue().getLabelPayload();
    }

    private LsDataLbl label(Long srcSn) {
        LsDataLbl label = LsDataLbl.createRestored(srcSn, LsDataLbl.TYPE_BBOX, null, "person",
                "[[0.0,0.0],[10.0,10.0]]", null, null, null, null);
        // 영속 라벨은 항상 PK 를 갖는다 — 스냅샷 items[].id 의 원천이라 비워두면 실제와 다른 입력이 된다.
        ReflectionTestUtils.setField(label, "lblSn", 1L);
        return label;
    }

    private LsDataSrc frame(Long srcSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, frameNo, "/raw/" + frameNo + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataRaw raw() {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-D5", "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/clip.mp4", LocalDateTime.now(), 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        return raw;
    }
}
