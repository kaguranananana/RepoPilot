package com.repopilot.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(classes = RepoPilotServerApplication.class)
class RepoPilotServerApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldLoadSpringContext() {
        assertNotNull(applicationContext);
    }
}
