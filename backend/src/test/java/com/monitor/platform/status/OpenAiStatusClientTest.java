package com.monitor.platform.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiStatusClientTest {

    @Test
    void parsesStatuspageSummary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiStatusClient client = new OpenAiStatusClient(builder.build());

        server.expect(requestTo(OpenAiStatusClient.SUMMARY_URI))
                .andRespond(withSuccess("""
                        {
                          "page": {
                            "name": "OpenAI",
                            "url": "https://status.openai.com/",
                            "updated_at": "2026-10-25T02:03:04Z"
                          },
                          "status": {
                            "description": "All Systems Operational",
                            "indicator": "none"
                          },
                          "components": [
                            {"name": "Chat Completions", "status": "operational"}
                          ],
                          "incidents": []
                        }
                        """, MediaType.APPLICATION_JSON));

        OpenAiStatusSummary summary = client.fetch();

        assertThat(summary.page().updatedAt()).isEqualTo("2026-10-25T02:03:04Z");
        assertThat(summary.status().indicator()).isEqualTo("none");
        assertThat(summary.components()).singleElement()
                .satisfies(component -> {
                    assertThat(component.name()).isEqualTo("Chat Completions");
                    assertThat(component.status()).isEqualTo("operational");
                });
        server.verify();
    }
}