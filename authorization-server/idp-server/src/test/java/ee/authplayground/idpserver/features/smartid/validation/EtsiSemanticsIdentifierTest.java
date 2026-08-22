package ee.authplayground.idpserver.features.smartid.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing the identifier that decides which person a login resolves to.
 */
class EtsiSemanticsIdentifierTest {

    @Test
    void splitsIntoTypeCountryAndNationalId() {
        EtsiSemanticsIdentifier identifier = EtsiSemanticsIdentifier.parse("PNOEE-40404040009");

        assertThat(identifier.identityType()).isEqualTo("PNO");
        assertThat(identifier.country()).isEqualTo("EE");
        assertThat(identifier.nationalId()).isEqualTo("40404040009");
        assertThat(identifier.value()).isEqualTo("PNOEE-40404040009");
    }

    /**
     * The country half is load-bearing, not decoration.
     *
     * <h2>Same number, different people</h2>
     * SK's own demo set contains {@code PNOEE-40404040009} and
     * {@code PNOLT-40404040009}. National ID numbers are unique within a
     * country and nowhere else, so an identifier that dropped the country — or a
     * lookup that ignored it — would resolve one person's login to another
     * person's account. In two different countries. With a valid signature.
     */
    @Test
    void treatsTheSameNumberInDifferentCountriesAsDifferentPeople() {
        EtsiSemanticsIdentifier estonian = EtsiSemanticsIdentifier.parse("PNOEE-40404040009");
        EtsiSemanticsIdentifier lithuanian = EtsiSemanticsIdentifier.parse("PNOLT-40404040009");

        assertThat(estonian).isNotEqualTo(lithuanian);
        assertThat(estonian.nationalId()).isEqualTo(lithuanian.nationalId());
    }

    /**
     * Document numbers are rejected.
     *
     * <h2>The trap SK's own documentation sets</h2>
     * Every demo account is published as {@code PNOEE-40404040009-MOCK-Q}. That
     * suffix identifies a <b>device</b>, not a person, and belongs to the
     * document-number endpoints. Because it begins with a valid identifier, an
     * unanchored pattern would match the prefix and silently accept a device as
     * an identity — which is why the pattern is anchored and why this test
     * exists.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "PNOEE-40404040009-MOCK-Q",
            "PNOEE-30403039917-DEMO-Q"
    })
    void rejectsDocumentNumbers(String documentNumber) {
        assertThatThrownBy(() -> EtsiSemanticsIdentifier.parse(documentNumber))
                .isInstanceOf(SmartIdValidationException.class)
                .hasMessageContaining("Not an ETSI natural person semantics identifier");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "40404040009",          // no type or country
            "PNO-40404040009",      // no country
            "PNOEE40404040009",     // no hyphen
            "XXXEE-40404040009",    // unknown identity type
            "PNOee-40404040009",    // country must be upper case
            ""
    })
    void rejectsMalformedIdentifiers(String value) {
        assertThatThrownBy(() -> EtsiSemanticsIdentifier.parse(value))
                .isInstanceOf(SmartIdValidationException.class);
    }

    @Test
    void buildsFromFormFieldsAndNormalisesCountryCase() {
        assertThat(EtsiSemanticsIdentifier.of("ee", " 40404040009 ").value())
                .isEqualTo("PNOEE-40404040009");
    }
}
