package kr.co.cudo.authoring.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @RestController
    public static class PortalStub {
        @GetMapping("/v1/portal/test")
        public String test() {
            return "portal-ok";
        }
    }

    @RestController
    public static class IntegrationStub {
        @PostMapping("/v1/integration/control/test")
        public String test() {
            return "m2m-ok";
        }
    }

    /**
     * Phase 4 — /v1/export-api/** 경로 권한 분리 검증용 스텁.
     * 실제 ExportApiController 와 동일하게 hasAuthority('M2M_LEARNING_DATA') 가드.
     */
    @RestController
    public static class ExportApiStub {
        @GetMapping("/v1/export-api/test")
        @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('M2M_LEARNING_DATA')")
        public String test() {
            return "export-api-ok";
        }
    }
}
