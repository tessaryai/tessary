// SPDX-License-Identifier: Apache-2.0
package ai.tessary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TessaryApplication {
    public static void main(String[] args) {
        SpringApplication.run(TessaryApplication.class, args);
    }
}
