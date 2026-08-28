package kr.co.cudo.authoring.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@TestConfiguration
public class StubControllers {

    @RestController
    public static class ManageStub {
        @GetMapping("/v1/manage/test")
        public String test() {
            return "manage-ok";
        }
    }

    /**
     * 작업자 <b>전용</b> 자리 표본 — 역할 계층이 한 단계뿐임을 고정하는 데 쓴다(@design AC-125).
     *
     * <p>실물({@code ReviewController} 의 검수 제출 경로)은 영상·배정 픽스처가 필요해 계층 자체를
     * 보기 어렵다. 여기서는 인가만 남긴 표본으로 "관리자·검수자가 작업자 자리에 흘러들지 않는다"를
     * 직접 본다 — 계층에 {@code WORKER} 를 끼우면 이 스텁이 200 이 되어 시험이 RED 다.
     */
    @RestController
    public static class WorkerOnlyStub {
        @GetMapping("/v1/worker-only/test")
        @PreAuthorize("hasRole('WORKER')")
        public String test() {
            return "worker-ok";
        }
    }

    @RestController
    public static class PortalStub {
        @GetMapping("/v1/portal/test")
        public String test() {
            return "portal-ok";
        }
    }
}
