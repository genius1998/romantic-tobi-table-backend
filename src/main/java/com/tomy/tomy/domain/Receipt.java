package com.tomy.tomy.domain;

import com.tomy.tomy.domain.Store;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "receipt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = true) // Made nullable
    private Store store;

    private String storeName;

    private String address;

    @Column(name = "total_price")
    private Integer totalPrice;

    @Column(name = "recognized_text", nullable = false)
    private String recognizedText;

    @Column(name = "recognized_date")
    private LocalDate recognizedDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
