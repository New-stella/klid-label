package kr.co.cudo.authoring.sysconfig.dto;

import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;

import java.time.LocalDateTime;

public record ConfigResponse(
        String configKey,
        String configValue,
        String configType,
        String description,
        String updatedBy,
        LocalDateTime updatedAt
) {
    public static ConfigResponse from(LsSystemConfig e) {
        return new ConfigResponse(
                e.getConfigKey(),
                e.getConfigValue(),
                e.getConfigType(),
                e.getDescription(),
                e.getUpdatedBy(),
                e.getUpdatedAt()
        );
    }
}
