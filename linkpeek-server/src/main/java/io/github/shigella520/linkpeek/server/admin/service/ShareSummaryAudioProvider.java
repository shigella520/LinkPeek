package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioConfigRecord;

import java.io.IOException;

public interface ShareSummaryAudioProvider {
    boolean supports(String providerType);

    ShareSummaryAudioClient.AudioGenerationResult generate(ShareSummaryAudioConfigRecord config, String input) throws IOException, InterruptedException;
}
