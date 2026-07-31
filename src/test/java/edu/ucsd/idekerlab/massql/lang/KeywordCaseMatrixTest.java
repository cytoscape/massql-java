package edu.ucsd.idekerlab.massql.lang;

import static org.junit.jupiter.api.Assertions.*;

import edu.ucsd.idekerlab.massql.Massql;
import edu.ucsd.idekerlab.massql.MassqlParseException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MassQL's keyword casing is ASYMMETRIC, and this test is what stops someone
 * "simplifying" the lexer into a case-insensitive one.
 *
 * <p>{@code QUERY}/{@code WHERE}/{@code AND}/{@code MS1DATA}/{@code MS2DATA}/
 * {@code POSITIVE}/{@code NEGATIVE} have case variants. {@code FILTER}, {@code OR}, every
 * condition name and every qualifier name do not. Function names are lowercase only.
 */
class KeywordCaseMatrixTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100",
        "query scaninfo(MS2DATA) where MS2PROD=100",
        "Query scaninfo(MS2DATA) Where MS2PROD=100",
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 AND MS2PREC=200",
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 and MS2PREC=200",
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 And MS2PREC=200",
        "QUERY scaninfo(MS1DATA) WHERE MS1MZ=100",
        "QUERY scaninfo(ms1data) WHERE MS1MZ=100",
        "QUERY scaninfo(Ms1Data) WHERE MS1MZ=100",
        "QUERY scaninfo(ms2data) WHERE MS2PROD=100",
        "QUERY scaninfo(Ms2Data) WHERE MS2PROD=100",
        "QUERY scaninfo(MS1DATA) WHERE POLARITY=POSITIVE",
        "QUERY scaninfo(MS1DATA) WHERE POLARITY=positive",
        "QUERY scaninfo(MS1DATA) WHERE POLARITY=Positive",
        "QUERY scaninfo(MS1DATA) WHERE POLARITY=NEGATIVE",
        "QUERY scaninfo(MS1DATA) WHERE POLARITY=negative",
        "QUERY scaninfo(MS1DATA) WHERE POLARITY=Negative",
    })
    void acceptedCaseVariants(String query) {
        assertDoesNotThrow(() -> Massql.parse(query), "must accept: " + query);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // FILTER has no lowercase or mixed form in the source grammar.
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 filter MS2PROD=200",
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 Filter MS2PROD=200",
        // OR likewise.
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 or 200)",
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 Or 200)",
        // Condition and qualifier names are strictly uppercase.
        "QUERY scaninfo(MS2DATA) WHERE ms2prod=100",
        "QUERY scaninfo(MS2DATA) WHERE Ms2Prod=100",
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:tolerancemz=0.1",
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:ToleranceMz=0.1",
        "QUERY scaninfo(MS1DATA) WHERE rtmin=50",
        // Function names are lowercase only.
        "QUERY SCANINFO(MS2DATA) WHERE MS2PROD=100",
        "QUERY ScanInfo(MS2DATA) WHERE MS2PROD=100",
    })
    void rejectedCaseVariants(String query) {
        assertThrows(MassqlParseException.class, () -> Massql.parse(query), "must reject: " + query);
    }

    /** FILTER is accepted in its one legal casing, so the rejections above are about case. */
    @ParameterizedTest
    @ValueSource(strings = {
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 FILTER MS2PROD=200",
        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 OR 200)",
    })
    void filterAndOrAreAcceptedInUppercase(String query) {
        assertDoesNotThrow(() -> Massql.parse(query), "must accept: " + query);
    }
}
