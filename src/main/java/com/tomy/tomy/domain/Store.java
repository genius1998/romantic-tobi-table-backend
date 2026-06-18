package com.tomy.tomy.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "store")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(name = "jibun_address")
    private String jibunAddress;

    private String district;

    @Column(name = "primary_category")
    private String primaryCategory;

    @Column(name = "secondary_category")
    private String secondaryCategory;

    private String phone;

    @Column(name = "coord_x")
    private Double coordX;

    @Column(name = "coord_y")
    private Double coordY;

    private BigDecimal area;

    @Column(name = "licensed_at")
    private LocalDate licensedAt;

    @Column(columnDefinition = "TEXT")
    private String aliases;

    @Column(name = "reward_eligible", nullable = false)
    private Boolean rewardEligible = true;
}
