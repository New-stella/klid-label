package kr.co.cudo.authoring.batch.test;

import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.batch.test.dto.AutolabelRunResponse;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 오토라벨링 파이프라인 테스트 트리거 — 운영(prd) 미노출.
 * 외부 의존(비식별/Gitea/VLM) 없이 YOLO → SAM2 만 실행한다.
 */
@RestController
@RequestMapping("/v1/dev/autolabel")
@RequiredArgsConstructor
@Validated
@Profile("!prd")
public class AutolabelTestController {

    private final AutolabelTestService autolabelTestService;

    @PostMapping("/run")
    public ApiResponse<AutolabelRunResponse> run(@RequestParam @Min(1) Long rawSn) {
        return ApiResponse.ok(autolabelTestService.run(rawSn));
    }
}
