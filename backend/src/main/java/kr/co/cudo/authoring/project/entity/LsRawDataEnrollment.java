package kr.co.cudo.authoring.project.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "LS_RAW_DATA_ENROLLMENT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsRawDataEnrollment {

    @Id
    @Column(name = "RAW_DATA_ID")
    private Long rawDataId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;
}
