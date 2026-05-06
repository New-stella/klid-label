package kr.co.cudo.authoring.common.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Health", description = "헬스체크 — 인증 불필요. 운영자 모니터링용 경량 상태 점검.")
@RestController
@RequestMapping("/health")
public class HealthController {

    private final Environment environment;
    private final String version;

    public HealthController(Environment environment,
                            @Value("${spring.application.name:authoring}") String appName) {
        this.environment = environment;
        this.version = appName;
    }

    @Operation(
            summary = "애플리케이션 헬스체크",
            description = "현재 활성 프로파일과 애플리케이션 이름을 반환한다. 인증 불필요(permitAll)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "정상 동작 — status=UP")
    })
    @GetMapping
    public ApiResponse<Map<String, String>> health() {
        String[] profiles = environment.getActiveProfiles();
        String env = profiles.length > 0 ? profiles[0] : "default";
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "env", env,
                "version", version
        ));
    }
}
