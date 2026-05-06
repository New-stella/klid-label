package kr.co.cudo.authoring.sysconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 시스템 설정 엔티티 (DB설계서 §5.2 LS_SYSTEM_CONFIG).
 * <p>
 * - PK = CONFIG_KEY (String). 4개 키 화이트리스트(ConfigKeys.ALLOWED) 만 사용.
 * - CONFIG_TYPE = NUMBER / STRING / JSON / BOOLEAN.
 * - Setter 금지 — 비즈니스 메서드(updateValue) 로만 변경.
 */
@Entity
@Table(name = "LS_SYSTEM_CONFIG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsSystemConfig {

    @Id
    @Column(name = "CONFIG_KEY", length = 100, nullable = false)
    private String configKey;

    @Column(name = "CONFIG_VALUE", length = 500)
    private String configValue;

    @Column(name = "CONFIG_TYPE", length = 20, nullable = false)
    private String configType;

    @Column(name = "DESCRIPTION", length = 500)
    private String description;

    @Column(name = "UPDATED_BY", length = 50)
    private String updatedBy;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    private LsSystemConfig(String configKey, String configValue, String configType,
                           String description, String updatedBy) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.configType = configType;
        this.description = description;
        this.updatedBy = updatedBy;
        this.updatedAt = LocalDateTime.now();
    }

    public static LsSystemConfig create(String key, String value, String type,
                                        String description, String updatedBy) {
        return new LsSystemConfig(key, value, type, description, updatedBy);
    }

    /** 값 갱신 — 비즈니스 메서드. CONFIG_TYPE 변경은 별도 마이그레이션 책임. */
    public void updateValue(String newValue, String updatedBy) {
        this.configValue = newValue;
        this.updatedBy = updatedBy;
        this.updatedAt = LocalDateTime.now();
    }
}
