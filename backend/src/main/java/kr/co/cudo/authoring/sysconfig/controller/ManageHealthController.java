package kr.co.cudo.authoring.sysconfig.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 시스템 설정 화면 — 외부 의존성 헬스 요약 (REVIEWER 전용).
 *
 * <p>FE 의 /manage/health 호출에 대응. Spring Actuator 의 외부 인디케이터
 * (deidentify / ai-server / gitea) + DB 핑을 단일 응답으로 요약한다.
 *
 * <p>관제/포털 양방향 통합은 deprecated 되어 헬스 체크 대상에서 제외.
 */
@Tag(name = "Manage Health", description = "시스템 설정 화면용 외부 의존성 헬스 요약 — REVIEWER 전용. /v1/manage/health.")
@Slf4j
@RestController
@RequestMapping("/v1/manage/health")
@SecurityRequirement(name = "bearerAuth")
public class ManageHealthController {

    private final HealthIndicator deidentifyHealth;
    private final HealthIndicator aiServerHealth;
    private final HealthIndicator giteaHealth;
    private final DataSource controlDataSource;

    public ManageHealthController(
            @Qualifier("deidentifyHealth")    HealthIndicator deidentifyHealth,
            @Qualifier("aiServerHealth")      HealthIndicator aiServerHealth,
            @Qualifier("giteaHealth")         HealthIndicator giteaHealth,
            @Qualifier("controlDataSource")   DataSource controlDataSource) {
        this.deidentifyHealth    = deidentifyHealth;
        this.aiServerHealth      = aiServerHealth;
        this.giteaHealth         = giteaHealth;
        this.controlDataSource   = controlDataSource;
    }

    @Operation(
            summary = "외부 의존성 헬스 요약 (REVIEWER)",
            description = "deidentify / ai-server / gitea + DB 의 상태를 단일 응답으로 반환."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("deidentify",    toComponent(deidentifyHealth));
        components.put("aiServer",      toComponent(aiServerHealth));
        components.put("gitea",         toComponent(giteaHealth));
        components.put("database",      probeDatabase());

        boolean allUp = components.values().stream()
                .map(c -> ((Map<?, ?>) c).get("status"))
                .allMatch(s -> "UP".equals(s));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DEGRADED");
        body.put("components", components);
        return ApiResponse.ok(body);
    }

    private Map<String, Object> toComponent(HealthIndicator indicator) {
        Map<String, Object> view = new LinkedHashMap<>();
        try {
            Health h = indicator.health();
            view.put("status", h.getStatus().getCode());
            view.put("details", h.getDetails());
        } catch (Exception e) {
            view.put("status", "DOWN");
            view.put("details", Map.of("error", e.getClass().getSimpleName()));
        }
        return view;
    }

    private Map<String, Object> probeDatabase() {
        Map<String, Object> view = new LinkedHashMap<>();
        try (Connection conn = controlDataSource.getConnection()) {
            boolean valid = conn.isValid(2);
            view.put("status", valid ? "UP" : "DOWN");
            view.put("details", Map.of("service", "control-db"));
        } catch (Exception e) {
            view.put("status", "DOWN");
            view.put("details", Map.of("service", "control-db", "error", e.getClass().getSimpleName()));
        }
        return view;
    }
}
