// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EvalsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvalsApplication.class, args);
    }
}
