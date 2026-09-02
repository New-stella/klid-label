package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 파생 복사 원본 경로 조달의 <b>단일 진실원</b> 회귀 가드. [design: ADR-058] [design: ERD-028]
 *
 * <h3>이 시험이 지키는 것</h3>
 * <p>「비식별이 끝났나」를 조건으로 되살리지 못하게 막는다. 그것은 <b>조건이 아니라 결과</b>였고
 * (관제의 진짜 전제조건은 검수 완료다) 비식별을 하지 않는 포털 업로드 자산의 증강을 통째로 막았다.
 * 동시에 <b>관제 원본이 새지 않는</b>다는 반대편 불변식도 함께 고정한다 — 출처를 판별하지 못하면
 * 비식별본을 찾다가 저절로 실패해야 한다(fail-closed 가 구조로 보장된다).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DerivativeSourceVideoResolverTest {

    private static final String DEID_BASE = "/storage/deidentified";
    private static final String PORTAL_BASE = "/storage/raw/portal";

    @Mock LsDeidentProcLogRepository deidentProcLogRepository;

    private DerivativeSourceVideoResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DerivativeSourceVideoResolver(
                deidentProcLogRepository,
                // co-locate 판정은 이 시험의 관심사가 아니다 — 비식별 저장소 서브트리 경로만 쓴다.
                null,
                portalProperties());
        ReflectionTestUtils.setField(resolver, "storageDeidentifiedPath", DEID_BASE);
    }

    // ─────────────────────────────────────────────────────────────
    // 출처 판별 — 기본값이 비식별본이어야 한다(fail-closed)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★출처를_판별하지_못하면_비식별본으로_떨어진다 — 관제_원본이_새지_않는다")
    void 판별_실패는_비식별본으로_떨어진다() {
        // 출처유형이 비어 있거나(과거 행) 우리가 모르는 값이어도 포털로 새지 않는다.
        assertThat(resolver.sourceOf(rawWithSrcType(null)))
                .isEqualTo(DerivativeSourceVideoResolver.Source.DEIDENTIFIED);
        assertThat(resolver.sourceOf(rawWithSrcType("SOME_FUTURE_VALUE")))
                .isEqualTo(DerivativeSourceVideoResolver.Source.DEIDENTIFIED);
        assertThat(resolver.sourceOf(null))
                .isEqualTo(DerivativeSourceVideoResolver.Source.DEIDENTIFIED);

        for (String srcType : List.of(LsDataRaw.SRC_TYPE_AUGMENTED, LsDataRaw.SRC_TYPE_GENERATED,
                LsDataRaw.SRC_TYPE_IMPORTED, "ORIGINAL", "RELAY")) {
            assertThat(resolver.sourceOf(rawWithSrcType(srcType)))
                    .as("관제 계열 출처 %s", srcType)
                    .isEqualTo(DerivativeSourceVideoResolver.Source.DEIDENTIFIED);
        }

        assertThat(resolver.sourceOf(rawWithSrcType(LsDataRaw.SRC_TYPE_PORTAL_ULD)))
                .isEqualTo(DerivativeSourceVideoResolver.Source.PORTAL_ORIGINAL);
    }

    // ─────────────────────────────────────────────────────────────
    // 관제 — 동작이 그대로다(원본 폴백 없음)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("관제_자산은_처리_이력에_적재된_비식별본_경로를_그대로_읽는다 — 파일명을_조합하지_않는다")
    void 관제는_적재된_비식별_경로를_읽는다() {
        LsDataRaw parent = rawWithSrcType("ORIGINAL");
        // KPST 는 '{원본stem}-mask{ext}', mock 은 'deidentified.mp4' 로 이름이 다르다 — 조합 금지.
        givenDeidProcLog(parent.getRawSn(), DEID_BASE + "/videos/7/7-mask.mp4");

        assertThat(resolver.requireSourceVideo(parent))
                .isEqualTo(Path.of(DEID_BASE + "/videos/7/7-mask.mp4"));
    }

    @Test
    @DisplayName("★관제_자산에_비식별_산출물이_없으면_원본으로_폴백하지_않고_실패한다")
    void 관제는_원본으로_폴백하지_않는다() {
        LsDataRaw parent = rawWithSrcType("ORIGINAL");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(anyLong()))
                .thenReturn(Optional.empty());

        // 원본 경로(RAW_FILE_PATH_NM)는 채워져 있다 — 그런데도 그것으로 떨어지면 안 된다.
        assertThat(parent.getRawFilePathNm()).isNotBlank();
        assertThatThrownBy(() -> resolver.requireSourceVideo(parent))
                .isInstanceOf(CustomException.class);
        assertThat(resolver.resolveQuietly(parent)).isEmpty();
    }

    @Test
    @DisplayName("관제_비식별_경로가_허용_저장경로_밖이면_거부한다")
    void 관제_경로_이탈은_거부된다() {
        LsDataRaw parent = rawWithSrcType("ORIGINAL");
        givenDeidProcLog(parent.getRawSn(), "/etc/passwd");

        assertThat(resolver.resolveQuietly(parent)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // 포털 — 본인 원본
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★포털_업로드_자산은_본인_원본을_복사한다 — 비식별_처리_이력을_보지_않는다")
    void 포털은_본인_원본을_쓴다() {
        LsDataRaw parent = portalRaw(PORTAL_BASE + "/tus-video/abc.mp4");

        assertThat(resolver.requireSourceVideo(parent))
                .isEqualTo(Path.of(PORTAL_BASE + "/tus-video/abc.mp4"));
        // 비식별을 하지 않는 채널이므로 처리 이력을 조회할 이유가 없다.
        verify(deidentProcLogRepository, never()).findLatestSuccessByDataRawSn(anyLong());
    }

    @Test
    @DisplayName("★포털_자산은_비식별_플래그가_미수행이어도_막히지_않는다 — 그_판정은_걷어냈다")
    void 포털은_비식별_플래그로_막히지_않는다() {
        LsDataRaw parent = portalRaw(PORTAL_BASE + "/tus-video/abc.mp4");

        // 되살리기 금지 축: 이 값이 'N' 이라는 이유로 막으면 포털 증강이 전건 실패한다.
        assertThat(parent.getDeIdntfYn()).isEqualTo("N");
        assertThat(parent.hasDeidentArtifact()).isFalse();
        assertThat(resolver.resolveQuietly(parent)).isPresent();
    }

    @Test
    @DisplayName("포털_원본_경로가_업로드_저장루트_밖이면_거부한다 — 비식별본으로_폴백하지_않는다")
    void 포털_경로_이탈은_거부된다() {
        LsDataRaw parent = portalRaw("/etc/passwd");
        givenDeidProcLog(parent.getRawSn(), DEID_BASE + "/videos/7/deidentified.mp4");

        assertThat(resolver.resolveQuietly(parent)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // 픽스처
    // ─────────────────────────────────────────────────────────────

    private void givenDeidProcLog(Long rawSn, String path) {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null, "/storage/raw/7.mp4", "test");
        procLog.succeed(path);
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.of(procLog));
    }

    private static LsDataRaw rawWithSrcType(String srcType) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-7", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/7.mp4", null, 60, srcType);
        setField(raw, "rawSn", 7L);
        return raw;
    }

    /** 흡수된 포털 업로드 자산 — 출처 판별자 + 소유자 + 본인 업로드 파일 경로. */
    private static LsDataRaw portalRaw(String filePath) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "PORTAL_ULD_7", null, null, null,
                LsDataRaw.PRVC_TYPE_UNKNOWN, filePath, null, null,
                LsDataRaw.SRC_TYPE_PORTAL_ULD);
        setField(raw, "rawSn", 7L);
        setField(raw, "portalUserNo", "portal-user-1");
        return raw;
    }

    private static PortalUploadProperties portalProperties() {
        return new PortalUploadProperties(
                5368709120L, List.of("mp4", "mov", "avi"), PORTAL_BASE,
                List.of("jpg", "jpeg", "png"), 20971520L, 50, 2000,
                16777216L, 2097152L, 30L, 30L);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
