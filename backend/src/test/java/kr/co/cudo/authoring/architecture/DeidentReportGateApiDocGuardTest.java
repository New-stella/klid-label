package kr.co.cudo.authoring.architecture;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.evntanno.controller.EvntAnnoController;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.dto.EvntAnnoRejectRequest;
import kr.co.cudo.authoring.label.controller.LabelAttrController;
import kr.co.cudo.authoring.label.dto.LabelAttrValueUpsertRequest;
import kr.co.cudo.authoring.review.controller.ReviewController;
import kr.co.cudo.authoring.review.dto.ApproveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비식별 누락 신고 게이트(412)가 걸린 엔드포인트의 <b>OpenAPI 문서화 가드</b>.
 *
 * <p><b>왜 필요한가</b>: 게이트는 서비스에 배선되고 문서는 컨트롤러에 적히므로 <b>두 곳이 조용히
 * 갈라진다</b>. 문서에 412 가 없으면 이 API 를 소비하는 FE(외부 팀 포함)는 그 응답을 계약에 없는
 * 예외로 취급해 "알 수 없는 오류"로 흘리거나 401/500 처리 분기로 잘못 라우팅한다. 게이트가 의도적
 * 차단(재비식별 대기 안내)인데 사용자에게는 장애로 보이는 것이다.
 *
 * <p><b>리플렉션으로 검사하는 이유</b>: OpenAPI 문서를 실제로 생성하려면 Spring 컨텍스트(+springdoc)가
 * 필요해 느리고, 컨텍스트 기동 실패가 이 가드의 실패로 위장된다. 어노테이션은 컴파일 산출물이라
 * 컨텍스트 없이도 결정론적으로 읽힌다.
 *
 * <p><b>음성 케이스를 함께 고정한다</b> — 게이트가 걸리지 <b>않은</b> 핸들러가 412 를 광고하면 그것도
 * 계약 거짓말이다(FE 가 오지 않는 응답을 위한 분기를 유지하게 된다). 특히
 * {@code GET /v1/videos/{rawSn}/event-annotation} 은 "조회 차단 범위를 라벨 좌표·프레임 이미지로
 * 한정한다"는 확정 정책에 따라 <b>의도적으로 열려 있다</b> — 문서에 412 를 넣으면 그 정책과 어긋난다.
 */
class DeidentReportGateApiDocGuardTest {

    private static final String GATE_STATUS = "412";

    // ── 게이트가 걸린 6 핸들러 (서비스에 requireNotUnderDeidentReport 배선됨) ─────────────

    @Test
    @DisplayName("객체별_속성값_조회는_비식별신고_412를_문서화한다")
    void 속성값_조회_412_문서화() throws Exception {
        assertDocumentsGate(LabelAttrController.class, "listAttrValues", Long.class, TokenClaims.class);
    }

    @Test
    @DisplayName("객체별_속성값_저장은_비식별신고_412를_문서화한다")
    void 속성값_저장_412_문서화() throws Exception {
        assertDocumentsGate(LabelAttrController.class, "upsertAttrValues",
                Long.class, LabelAttrValueUpsertRequest.class, TokenClaims.class);
    }

    @Test
    @DisplayName("event_annotation_저장은_비식별신고_412를_문서화한다")
    void event_annotation_저장_412_문서화() throws Exception {
        assertDocumentsGate(EvntAnnoController.class, "upsert",
                Long.class, EventAnnotationPayload.class, TokenClaims.class);
    }

    @Test
    @DisplayName("event_annotation_검토승인은_비식별신고_412를_문서화한다")
    void event_annotation_승인_412_문서화() throws Exception {
        assertDocumentsGate(EvntAnnoController.class, "approve", Long.class, TokenClaims.class);
    }

    @Test
    @DisplayName("event_annotation_검토반려는_비식별신고_412를_문서화한다")
    void event_annotation_반려_412_문서화() throws Exception {
        assertDocumentsGate(EvntAnnoController.class, "reject",
                Long.class, EvntAnnoRejectRequest.class, TokenClaims.class);
    }

    @Test
    @DisplayName("검수_승인은_비식별신고_412를_문서화한다")
    void 검수_승인_412_문서화() throws Exception {
        assertDocumentsGate(ReviewController.class, "approve",
                Long.class, ApproveRequest.class, TokenClaims.class);
    }

    // ── 음성 케이스: 게이트가 없는 핸들러는 412 를 광고하지 않는다 ────────────────────────

    @Test
    @DisplayName("event_annotation_조회는_게이트_대상이_아니므로_412를_문서화하지_않는다")
    void event_annotation_조회는_412_미문서화() throws Exception {
        Method handler = EvntAnnoController.class.getMethod("get", Long.class, TokenClaims.class);
        assertThat(declaredStatusCodes(handler))
                .as("조회는 확정 정책상 신고 구간에도 열려 있다 — 412 를 문서화하면 계약 거짓말이 된다")
                .doesNotContain(GATE_STATUS);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private void assertDocumentsGate(Class<?> controller, String method, Class<?>... params)
            throws NoSuchMethodException {
        Method handler = controller.getMethod(method, params);
        String[] codes = declaredStatusCodes(handler);
        assertThat(codes)
                .as("%s#%s 에 412(비식별 누락 신고 구간) 문서화 누락 — 서비스에는 게이트가 배선돼 있다",
                        controller.getSimpleName(), method)
                .contains(GATE_STATUS);
        // 코드만 맞고 설명이 비면 문서로서 쓸모가 없다(FE 가 무엇을 안내할지 알 수 없다).
        assertThat(descriptionOf(handler, GATE_STATUS))
                .as("%s#%s 의 412 설명이 비어 있다", controller.getSimpleName(), method)
                .isNotBlank();
    }

    private String[] declaredStatusCodes(Method handler) {
        ApiResponses responses = handler.getAnnotation(ApiResponses.class);
        if (responses == null) {
            return new String[0];
        }
        return Arrays.stream(responses.value()).map(ApiResponse::responseCode).toArray(String[]::new);
    }

    private String descriptionOf(Method handler, String statusCode) {
        ApiResponses responses = handler.getAnnotation(ApiResponses.class);
        if (responses == null) {
            return "";
        }
        return Arrays.stream(responses.value())
                .filter(r -> statusCode.equals(r.responseCode()))
                .map(ApiResponse::description)
                .findFirst()
                .orElse("");
    }
}
