package com.tomy.tomy.dto;

import java.util.List;

public class ChatbotMessageResponse {

    private String answer;
    private String intent;
    private List<ChatbotSourceDto> sources;
    private List<ChatbotActionDto> actions;

    public ChatbotMessageResponse() {}

    public ChatbotMessageResponse(String answer, String intent,
                                   List<ChatbotSourceDto> sources,
                                   List<ChatbotActionDto> actions) {
        this.answer = answer;
        this.intent = intent;
        this.sources = sources;
        this.actions = actions;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public List<ChatbotSourceDto> getSources() { return sources; }
    public void setSources(List<ChatbotSourceDto> sources) { this.sources = sources; }

    public List<ChatbotActionDto> getActions() { return actions; }
    public void setActions(List<ChatbotActionDto> actions) { this.actions = actions; }
}
