package com.tomy.tomy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tomy.tomy.domain.Store;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatbotStoreDto {
    private Long storeId;
    private String name;
    private String address;
    private String district;
    private String primaryCategory;
    private String secondaryCategory;
    private String aliases;
    private Boolean rewardEligible;

    public static ChatbotStoreDto from(Store s) {
        return new ChatbotStoreDto(
                s.getId(),
                s.getName(),
                s.getAddress(),
                s.getDistrict(),
                s.getPrimaryCategory(),
                s.getSecondaryCategory(),
                s.getAliases(),
                s.getRewardEligible()
        );
    }
}
