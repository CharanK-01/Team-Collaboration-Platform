package com.charan.backend.health;

import com.charan.backend.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest extends BaseIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void health_withoutAuth_returns200OK() {
        ResponseEntity<String> resp = restTemplate.getForEntity("/health", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo("OK");
    }
}
