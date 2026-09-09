// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import ai.tessary.config.IngestSpoolProperties;
import ai.tessary.config.SubstrateProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the spool from {@code tessary.ingest.spool.mode}; {@code memory} unless an operator opts into a broker. */
@Configuration
public class IngestSpoolConfig {

    @Bean
    @ConditionalOnProperty(name = "tessary.ingest.spool.mode", havingValue = "memory", matchIfMissing = true)
    public IngestSpool inProcessSpool(SubstrateProperties props) {
        return new InProcessSpool(props);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "tessary.ingest.spool.mode", havingValue = "kafka")
    public IngestSpool kafkaSpool(IngestSpoolProperties props, ObjectMapper mapper) {
        return new KafkaSpool(props.getKafka(), mapper);
    }
}
