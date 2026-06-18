package com.tomy.tomy.dto;

public class ChatbotActionDto {

    private String type;
    private String label;
    private String target;

    public ChatbotActionDto() {}

    public ChatbotActionDto(String type, String label, String target) {
        this.type = type;
        this.label = label;
        this.target = target;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
}
