package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.review.dto.ReviewResponse;
import kr.co.cudo.authoring.video.dto.CctvDisplayNamePolicy;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면 표시명(CCTV명) 폴백 계약 — 목록·상세가 <b>같은 규칙</b>으로 이름을 만든다 (V185).
 *
 * <h3>왜 3순위가 생겼나</h3>
 * <p>관제 회신(2026-08-12)으로 {@code VMS_CCTV_ID} 가 NULL 인 영상이 존재하게 됐다. 기존 2단
 * 폴백(CCTV명 → CCTV ID)은 <b>둘 다 없으면 빈칸</b>을 그려, 목록에서 그 영상을 <b>식별할 수 없다</b>.
 * 3순위로 {@code 영상 #{rawSn}} 을 둔다 — 이 표기는 화면에서 이미 쓰는 관례이며 rawSn 은 항상 있다.
 *
 * <h3>판정은 한 곳에서만 한다</h3>
 * <p>목록·상세가 각자 삼항식을 들면 한쪽만 갱신돼 같은 영상이 화면마다 다른 이름으로 보인다.
 * {@link CctvDisplayNamePolicy} 가 단일 진실원이고 두 DTO 는 그것을 호출만 한다.
 */
class CctvDisplayNameFallbackTest {

    private static LsDataRaw raw(long rawSn, String vmsCctvId) {
        LsDataRaw e = LsDataRaw.createFromIngest("CLIP-" + rawSn, vmsCctvId, "EV01", "11680",
                "PRVC", "/nas-storage/raw/clip.mp4", null, 30);
        ReflectionTestUtils.setField(e, "rawSn", rawSn);
        return e;
    }

    @Test
    @DisplayName("CCTV명이_있으면_그대로_쓴다")
    void CCTV명이_있으면_그대로_쓴다() {
        // given / when / then — 1순위는 관제 인입 평면값(CCTV_NM)이다.
        assertThat(CctvDisplayNamePolicy.resolve("유성구 어은동 사거리", "CCTV-001", 501L))
                .isEqualTo("유성구 어은동 사거리");
    }

    @Test
    @DisplayName("CCTV명이_없으면_CCTV_ID로_폴백한다")
    void CCTV명이_없으면_CCTV_ID로_폴백한다() {
        // given / when / then — 기존 2단 폴백은 그대로다(회귀 가드). 공백만도 "없음"으로 본다.
        assertThat(CctvDisplayNamePolicy.resolve(null, "CCTV-001", 501L)).isEqualTo("CCTV-001");
        assertThat(CctvDisplayNamePolicy.resolve("   ", "CCTV-001", 501L)).isEqualTo("CCTV-001");
    }

    @Test
    @DisplayName("CCTV명과_CCTV_ID가_모두_없으면_영상번호로_식별한다")
    void CCTV명과_CCTV_ID가_모두_없으면_영상번호로_식별한다() {
        // given / when / then — 빈칸·null 을 그대로 노출하면 목록에서 행을 구분할 수 없다.
        assertThat(CctvDisplayNamePolicy.resolve(null, null, 501L)).isEqualTo("영상 #501");
        assertThat(CctvDisplayNamePolicy.resolve("  ", "  ", 501L)).isEqualTo("영상 #501");
    }

    @Test
    @DisplayName("목록_응답의_영상명이_폴백_규칙을_따른다")
    void 목록_응답의_영상명이_폴백_규칙을_따른다() {
        // given — CCTV명·CCTV ID 가 둘 다 없는 영상(관제 수동 업로드분)
        VideoSummaryResponse res = VideoSummaryResponse.from(raw(501L, null), null, null, 0L);

        // then — 화면이 빈칸을 그리지 않는다. 원본 컬럼(vmsCctvId)은 null 그대로 나간다.
        assertThat(res.cctvName()).isEqualTo("영상 #501");
        assertThat(res.vmsCctvId()).isNull();
    }

    @Test
    @DisplayName("상세_응답의_영상명이_목록과_같은_규칙을_따른다")
    void 상세_응답의_영상명이_목록과_같은_규칙을_따른다() {
        // given — 같은 영상을 상세로 열었을 때 이름이 달라지면 안 된다.
        VideoDetailResponse res = VideoDetailResponse.from(raw(501L, null), null, null, 0L);

        // then
        assertThat(res.cctvName()).isEqualTo("영상 #501");
        assertThat(res.vmsCctvId()).isNull();
    }

    // ------------------------------------------------------------------
    // 다른 화면들도 같은 판정기를 쓴다 — 표기가 갈리면 같은 영상이 화면마다 다른 이름이 된다.
    // ------------------------------------------------------------------

    /** 검수 워크플로 상태 1건 — 검수목록 응답 조립의 최소 입력. */
    private static LsRawDataStatus status(long rawDataId) {
        return LsRawDataStatus.initial(rawDataId);
    }

    /** LABELER 배정 1건 — 배정목록(WORKER 작업목록) 응답 조립의 최소 입력. */
    private static LsTaskAssignment labeler(long rawDataId) {
        return LsTaskAssignment.createLabeler(rawDataId, 100L, 1L);
    }

    @Test
    @DisplayName("검수목록_영상명이_없으면_영상번호_표기로_폴백한다")
    void 검수목록_영상명이_없으면_영상번호_표기로_폴백한다() {
        // given — CCTV명·CCTV ID 를 조달하지 못한 영상(lookup 미스).
        // when
        ReviewResponse res = ReviewResponse.from(status(501L), null, null, null, 0L, null, null);

        // then — 구 표기 "video #501" 은 폐기됐다. 폴백 표기는 화면 전체에서 하나뿐이다.
        assertThat(res.cctvName()).isEqualTo("영상 #501");
    }

    @Test
    @DisplayName("검수목록_영상명이_있으면_그대로_쓴다")
    void 검수목록_영상명이_있으면_그대로_쓴다() {
        // given / when — 회귀 가드: 이름이 있는 영상은 폴백이 개입하지 않는다.
        ReviewResponse res = ReviewResponse.from(status(501L), "유성구 어은동 사거리",
                null, null, 0L, null, null);

        // then
        assertThat(res.cctvName()).isEqualTo("유성구 어은동 사거리");
    }

    @Test
    @DisplayName("배정목록_영상명이_없으면_영상명과_영상제목이_모두_영상번호_표기가_된다")
    void 배정목록_영상명이_없으면_영상명과_영상제목이_모두_영상번호_표기가_된다() {
        // given / when — WORKER 작업목록이 읽는 두 필드는 같은 판정기를 거친다.
        AssignmentResponse.Item item = AssignmentResponse.Item.from(
                labeler(501L), null, null, null, null, null, null);

        // then — FE 가 cctvName 을 그리므로 이 필드가 비면 행 전체가 빈칸이 된다.
        assertThat(item.cctvName()).isEqualTo("영상 #501");
        // videoTitle 은 외부 FE 계약 필드다(우리 테스트베드는 미사용). 값만 폴백 표기로 통일한다.
        assertThat(item.videoTitle()).isEqualTo("영상 #501");
    }

    @Test
    @DisplayName("배정목록_영상명이_있으면_영상명과_영상제목이_모두_그_이름이다")
    void 배정목록_영상명이_있으면_영상명과_영상제목이_모두_그_이름이다() {
        // given / when — 회귀 가드.
        AssignmentResponse.Item item = AssignmentResponse.Item.from(
                labeler(501L), null, null, "유성구 어은동 사거리", null, null, null);

        // then
        assertThat(item.cctvName()).isEqualTo("유성구 어은동 사거리");
        assertThat(item.videoTitle()).isEqualTo("유성구 어은동 사거리");
    }
}
