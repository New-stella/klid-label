package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.portal.service.PortalWorkableVideoPolicy;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 작업 가능 판정 — 「검수 승인」 <b>또는</b> 「출처 PORTAL_DATASET」(ADR-068).
 *
 * <h3>스텁은 요청 식별자를 본다</h3>
 * <p>인자와 무관하게 같은 값을 돌려주는 스텁은 판정기가 <b>엉뚱한 식별자로 물어도</b> 통과한다. 두 조회 모두
 * 요청 집합에 든 행만 돌려주게 해 「무엇을 물었는가」까지 시험이 문다.
 *
 * <h3>이 시험이 지키지 못하는 것</h3>
 * <p>판정기를 쓰는 창구가 <b>실제로 이 판정기를 부르는가</b>는 여기서 보지 않는다 — 실 DB 로 창구를
 * 왕복하는 {@code PortalDatasetRegistrationIT} 가 본다.
 *
 * @design ADR-068
 * @design AC-1120
 */
class PortalWorkableVideoPolicyTest {

    private LsRawDataStatusRepository statusRepository;
    private VideoRepository videoRepository;
    private PortalWorkableVideoPolicy policy;

    private final Map<Long, LsRawDataStatus> statuses = new HashMap<>();
    private final Map<Long, LsDataRaw> raws = new HashMap<>();

    @BeforeEach
    void setUp() {
        statusRepository = mock(LsRawDataStatusRepository.class);
        videoRepository = mock(VideoRepository.class);
        policy = new PortalWorkableVideoPolicy(statusRepository, videoRepository);
        when(statusRepository.findAllById(any())).thenAnswer(inv -> pick(inv.getArgument(0), statuses));
        when(videoRepository.findAllById(any())).thenAnswer(inv -> pick(inv.getArgument(0), raws));
    }

    private static <T> List<T> pick(Iterable<Long> ids, Map<Long, T> table) {
        List<T> out = new ArrayList<>();
        for (Long id : ids) {
            if (table.containsKey(id)) {
                out.add(table.get(id));
            }
        }
        return out;
    }

    private void status(long rawSn, String stts) {
        LsRawDataStatus s = LsRawDataStatus.initial(rawSn);
        setField(s, "dataSttsCd", stts);
        statuses.put(rawSn, s);
    }

    private void raw(long rawSn, LsDataRaw raw) {
        setField(raw, "rawSn", rawSn);
        raws.put(rawSn, raw);
    }

    private static LsDataRaw ingest() {
        return LsDataRaw.createFromIngest("CLIP-" + System.nanoTime(), "cctv", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30);
    }

    private static LsDataRaw dataset(long datasetId, String key) {
        return LsDataRaw.createPortalDataset(LsDataRaw.portalDatasetClipId(datasetId, key), "/materials/v");
    }

    @Test
    @DisplayName("검수_승인_영상은_작업_가능하다_승인_경로_무회귀")
    void approvedVideoIsWorkable() {
        status(1L, LsRawDataStatus.STTS_APPROVED);
        raw(1L, ingest());

        assertThat(policy.isWorkable(1L)).isTrue();
        // 승인으로 이미 참이면 원장 조회를 치르지 않는다.
        verify(videoRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("★검수_상태가_없어도_출처가_PORTAL_DATASET_이면_작업_가능하다")
    void portalDatasetWithoutStatusIsWorkable() {
        raw(2L, dataset(7L, "cam-a"));

        assertThat(policy.isWorkable(2L)).isTrue();
    }

    @Test
    @DisplayName("★승인도_데이터셋_등록도_아닌_영상은_작업_불가다")
    void neitherApprovedNorDatasetIsNotWorkable() {
        status(3L, LsRawDataStatus.STTS_PENDING);
        raw(3L, ingest());
        raw(4L, ingest()); // 상태 행 자체가 없다 — 「행 부재」와 「값 불일치」는 다른 갈래다

        assertThat(policy.isWorkable(3L)).isFalse();
        assertThat(policy.isWorkable(4L)).isFalse();
    }

    @Test
    @DisplayName("본인_업로드_자산은_이_판정의_대상이_아니다_소유_판정은_따로다")
    void portalUploadIsNotWorkableHere() {
        raw(5L, LsDataRaw.createPortalUpload("alice", "/portal/v.mp4"));

        assertThat(policy.isWorkable(5L)).isFalse();
    }

    @Test
    @DisplayName("행이_없거나_식별자가_비면_거짓이다")
    void missingOrNullIsNotWorkable() {
        assertThat(policy.isWorkable(999L)).isFalse();
        assertThat(policy.isWorkable(null)).isFalse();
        assertThat(policy.workable(null)).isEmpty();
        assertThat(policy.workable(List.of())).isEmpty();
    }

    @Test
    @DisplayName("★일괄_판정은_두_갈래를_합치고_요청한_식별자만_돌려준다")
    void batchMergesBothBranchesForRequestedIdsOnly() {
        status(10L, LsRawDataStatus.STTS_APPROVED);
        raw(10L, ingest());
        raw(11L, dataset(7L, "cam-b"));
        raw(12L, ingest());
        raw(13L, dataset(7L, "cam-c")); // 요청하지 않는다

        Collection<Long> result = policy.workable(List.of(10L, 11L, 12L));

        assertThat(result).containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("★조회가_요청하지_않은_행을_섞어_돌려줘도_결과에_싣지_않는다")
    void extraRowsFromRepositoryAreNotTrusted() {
        LsRawDataStatus foreign = LsRawDataStatus.initial(77L);
        setField(foreign, "dataSttsCd", LsRawDataStatus.STTS_APPROVED);
        LsDataRaw foreignRaw = dataset(7L, "cam-x");
        setField(foreignRaw, "rawSn", 78L);
        doReturn(List.of(foreign)).when(statusRepository).findAllById(any());
        doReturn(List.of(foreignRaw)).when(videoRepository).findAllById(any());

        assertThat(policy.isWorkable(1L)).isFalse();
        assertThat(policy.workable(List.of(1L))).isEmpty();
    }

    @Test
    @DisplayName("거부_문구는_한_상수다")
    void rejectionMessageIsSingleConstant() {
        assertThat(PortalWorkableVideoPolicy.NOT_WORKABLE_MESSAGE).isEqualTo("포털에서 작업할 수 없는 영상입니다.");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException ignore) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new IllegalStateException(name);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
