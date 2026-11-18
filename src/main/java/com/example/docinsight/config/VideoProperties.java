package com.example.docinsight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 视频摄取配置：原文件落盘，Whisper 只生成文本切片。 */
@ConfigurationProperties(prefix = "app.video")
public class VideoProperties {

    private boolean enabled = true;
    private String pythonCommand = "";
    private String model = "small";
    private String device = "auto";
    private String computeType = "int8";
    private String language = "auto";
    private String hfEndpoint = "";
    private long maxFileSizeMb = 500;
    private long maxDurationMinutes = 120;
    private String storageDir = "./data/media";
    private String modelDir = "./data/models";
    private int startupTimeoutSeconds = 600;
    private int transcriptionTimeoutMinutes = 120;
    private int workerIdleMinutes = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPythonCommand() {
        return pythonCommand;
    }

    public void setPythonCommand(String pythonCommand) {
        this.pythonCommand = pythonCommand;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public String getComputeType() {
        return computeType;
    }

    public void setComputeType(String computeType) {
        this.computeType = computeType;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getHfEndpoint() {
        return hfEndpoint;
    }

    public void setHfEndpoint(String hfEndpoint) {
        this.hfEndpoint = hfEndpoint;
    }

    public long getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public void setMaxFileSizeMb(long maxFileSizeMb) {
        this.maxFileSizeMb = maxFileSizeMb;
    }

    public long getMaxDurationMinutes() {
        return maxDurationMinutes;
    }

    public void setMaxDurationMinutes(long maxDurationMinutes) {
        this.maxDurationMinutes = maxDurationMinutes;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public String getModelDir() {
        return modelDir;
    }

    public void setModelDir(String modelDir) {
        this.modelDir = modelDir;
    }

    public int getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    public void setStartupTimeoutSeconds(int startupTimeoutSeconds) {
        this.startupTimeoutSeconds = startupTimeoutSeconds;
    }

    public int getTranscriptionTimeoutMinutes() {
        return transcriptionTimeoutMinutes;
    }

    public void setTranscriptionTimeoutMinutes(int transcriptionTimeoutMinutes) {
        this.transcriptionTimeoutMinutes = transcriptionTimeoutMinutes;
    }

    public int getWorkerIdleMinutes() {
        return workerIdleMinutes;
    }

    public void setWorkerIdleMinutes(int workerIdleMinutes) {
        this.workerIdleMinutes = workerIdleMinutes;
    }
}
