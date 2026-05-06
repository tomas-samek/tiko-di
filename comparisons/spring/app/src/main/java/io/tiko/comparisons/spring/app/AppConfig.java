package io.tiko.comparisons.spring.app;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = {
    "io.tiko.comparisons.spring.modulea",
    "io.tiko.comparisons.spring.moduleb"
})
public class AppConfig {
}
