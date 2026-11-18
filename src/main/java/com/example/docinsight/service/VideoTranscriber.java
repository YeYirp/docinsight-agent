package com.example.docinsight.service;

import java.nio.file.Path;

public interface VideoTranscriber {

    VideoTranscript transcribe(Path videoPath);
}
