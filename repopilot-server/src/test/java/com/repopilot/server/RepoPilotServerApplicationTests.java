package com.repopilot.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(
        classes = RepoPilotServerApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:repopilot-server-context;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=",
                "spring.datasource.password="
        }
)
class RepoPilotServerApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldLoadSpringContext() {
        assertNotNull(applicationContext);
    }
}
