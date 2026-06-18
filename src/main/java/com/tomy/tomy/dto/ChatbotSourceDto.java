package com.tomy.tomy.dto;

public class ChatbotSourceDto {

    private String sourceFile;
    private Integer chunkIndex;
    private String preview;

    public ChatbotSourceDto() {}

    public ChatbotSourceDto(String sourceFile, Integer chunkIndex, String preview) {
        this.sourceFile = sourceFile;
        this.chunkIndex = chunkIndex;
        this.preview = preview;
    }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getPreview() { return preview; }
    public void setPreview(String preview) { this.preview = preview; }
}
