// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.rca.AgenticRcaEngine;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * One shared context for the case and RCA classes, with the RCA drain parked and {@link AgenticRcaEngine} mocked.
 *
 * <p>batch-size=0 parks the scheduled drain (claimBatch's LIMIT 0), so a job stays {@code pending} and a direct
 * {@code run} is the only execution; a tick would otherwise claim the job first, or run it twice. A long heartbeat
 * cannot do this, since fixedDelay fires at startup. Static properties and a type-level mock keep the context cache
 * key identical for every class; a {@code @DynamicPropertySource} or a field {@code @MockitoBean} would fork one per
 * declaring class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@TestPropertySource(properties = {"tessary.rca.batch-size=0", "tessary.rca.heartbeat-ms=3600000"})
@MockitoBean(types = AgenticRcaEngine.class)
public @interface RcaParkedSpringBootTest {}
