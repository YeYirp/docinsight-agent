package com.example.docinsight.controller;

import com.example.docinsight.model.VideoIngestionJob;
import com.example.docinsight.service.VideoIngestionJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingestion-jobs")
public class IngestionJobController {

    private final VideoIngestionJobService jobs;

    public IngestionJobController(VideoIngestionJobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/{id}")
    public VideoIngestionJob get(@PathVariable String id) {
        return jobs.get(id);
    }
}
