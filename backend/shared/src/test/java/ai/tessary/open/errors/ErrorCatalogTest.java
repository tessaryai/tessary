// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/**
 * {@link ErrorCatalog}: two error enums that resolve to one domain refuse to boot, and every code the
 * catalog registers is an error status with a message to send.
 */
class ErrorCatalogTest {

    /** Two enums with the same simple name in different outer classes both resolve to domain {@code DUP}. */
    static final class First {
        enum DupError implements ErrorCode {
            X;

            @Override
            public HttpStatus status() {
                return HttpStatus.CONFLICT;
            }

            @Override
            public String template() {
                return "first";
            }

            @Override
            public Class<? extends Enum<?>> declaringClass() {
                return DupError.class;
            }
        }
    }

    static final class Second {
        enum DupError implements ErrorCode {
            X;

            @Override
            public HttpStatus status() {
                return HttpStatus.CONFLICT;
            }

            @Override
            public String template() {
                return "second";
            }

            @Override
            public Class<? extends Enum<?>> declaringClass() {
                return DupError.class;
            }
        }
    }

    /**
     * The bug: two domains that render the same wire code let a client (and the frontend's error map)
     * mistake one failure for the other. The catalog exists to stop that at boot, naming both enums.
     */
    @Test
    void twoEnumsResolvingToOneCodeFailBootNamingBoth() {
        ErrorCatalog catalog = new ErrorCatalog(List.of(First.DupError.class, Second.DupError.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class, catalog::validate);

        assertEquals(
                "Duplicate error code 'DUP.X' declared by both ai.tessary.open.errors.ErrorCatalogTest$First$DupError"
                        + " and ai.tessary.open.errors.ErrorCatalogTest$Second$DupError",
                ex.getMessage());
    }

    static Stream<ErrorCode> registeredCodes() {
        return Stream.of(
                        CommonError.class,
                        IngestError.class,
                        ModelConfigError.class,
                        VersionError.class,
                        GitError.class,
                        PipelineError.class,
                        ClassifierError.class,
                        AlertError.class,
                        PreDeployError.class,
                        QueryError.class,
                        RedactionError.class,
                        MeteringError.class,
                        CapabilityError.class,
                        RcaError.class,
                        CaseError.class,
                        AuthError.class,
                        DecisionError.class)
                .flatMap(c -> Arrays.stream(c.getEnumConstants()));
    }

    /**
     * The bug: an error code declared with a 2xx/3xx status sends {@code success:false} under a success
     * status, and one with a blank template sends an error envelope with nothing to show the user.
     */
    @ParameterizedTest
    @MethodSource("registeredCodes")
    void everyRegisteredCodeIsAnErrorStatusWithAMessage(ErrorCode code) {
        assertTrue(code.status().isError(), code.code() + " must answer 4xx/5xx, not " + code.status());
        assertFalse(code.template().isBlank(), code.code() + " must carry a message template");
    }
}
