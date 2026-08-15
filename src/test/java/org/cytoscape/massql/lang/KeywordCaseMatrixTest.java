package org.cytoscape.massql.lang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlParseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KeywordCaseMatrixTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
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
    @ValueSource(
            strings = {
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 filter MS2PROD=200",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 Filter MS2PROD=200",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 or 200)",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 Or 200)",
                "QUERY scaninfo(MS2DATA) WHERE ms2prod=100",
                "QUERY scaninfo(MS2DATA) WHERE Ms2Prod=100",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:tolerancemz=0.1",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:ToleranceMz=0.1",
                "QUERY scaninfo(MS1DATA) WHERE rtmin=50",
                "QUERY SCANINFO(MS2DATA) WHERE MS2PROD=100",
                "QUERY ScanInfo(MS2DATA) WHERE MS2PROD=100",
            })
    void rejectedCaseVariants(String query) {
        assertThrows(
                MassqlParseException.class, () -> Massql.parse(query), "must reject: " + query);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 FILTER MS2PROD=200",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 OR 200)",
            })
    void filterAndOrAreAcceptedInUppercase(String query) {
        assertDoesNotThrow(() -> Massql.parse(query), "must accept: " + query);
    }
}
