// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.spool;

import ai.tessary.evals.config.IngestSpoolProperties;
import ai.tessary.evals.config.SubstrateProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the spool from {@code evals.ingest.spool.mode}; {@code memory} unless an operator opts into a broker. */
@Configuration
public class IngestSpoolConfig {

    @Bean
    @ConditionalOnProperty(name = "evals.ingest.spool.mode", havingValue = "memory", matchIfMissing = true)
    public IngestSpool inProcessSpool(SubstrateProperties props) {
        return new InProcessSpool(props);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "evals.ingest.spool.mode", havingValue = "kafka")
    public IngestSpool kafkaSpool(IngestSpoolProperties props, ObjectMapper mapper) {
        return new KafkaSpool(props.getKafka(), mapper);
    }
}
