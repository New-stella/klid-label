package kr.co.cudo.authoring.sysconfig.dto;

import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;

import java.time.LocalDateTime;

public record ConfigResponse(
        String configKey,
        String configVl,
        String configTypeCd,
        String expln,
        String mdfrId,
        LocalDateTime mdfcnDt
) {
    public static ConfigResponse from(LsSystemConfig e) {
        return new ConfigResponse(
                e.getConfigKey(),
                e.getConfigVl(),
                e.getConfigTypeCd(),
                e.getExpln(),
                e.getMdfrId(),
                e.getMdfcnDt()
        );
    }
}
