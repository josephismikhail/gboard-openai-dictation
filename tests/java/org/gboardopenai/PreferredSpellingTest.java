package org.gboardopenai;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreferredSpellingTest {
    @Test public void restoresConfiguredCaseAtEveryWholeWord(){
        assertEquals("Acme and CloudBox. Acme's CloudBox!",PreferredSpelling.apply(
            "acme and CLOUDBOX. ACME's cloudbox!",Arrays.asList("Acme","CloudBox")));
    }
    @Test public void doesNotRewriteHeardAliasesOrNearMatches(){
        assertEquals("akme CloudBox clodbox",PreferredSpelling.apply(
            "akme CLOUDBOX clodbox",Arrays.asList("akme => Acme","clodbox => CloudBox")));
    }
    @Test public void unicodeLettersNumbersMarksAndUnderscoresKeepWordBoundaries(){
        assertEquals("éacme acmeé acme1 1acme acme_name acme\u0301 Acme",PreferredSpelling.apply(
            "éacme acmeé acme1 1acme acme_name acme\u0301 ACME",Collections.singletonList("Acme")));
    }
    @Test public void longerCanonicalPhrasesWinBeforeShorterWords(){
        assertEquals("ACME CloudBox and Acme",PreferredSpelling.apply(
            "acme cloudbox and acme",Arrays.asList("Acme","ACME CloudBox","CloudBox")));
    }
    @Test public void punctuationIsLiteralAndReplacementSyntaxIsNotInterpreted(){
        assertEquals("Acme+$ (CloudBox)",PreferredSpelling.apply(
            "acme+$ (CLOUDBOX)",Arrays.asList("Acme+$","CloudBox")));
    }
    @Test public void firstConfiguredSpellingWinsConflictingDuplicates(){
        assertEquals("CloudBox",PreferredSpelling.apply("cloudbox",Arrays.asList("CloudBox","CLOUDBOX")));
    }
    @Test public void leavesWhitespaceAndUnrelatedWordsUntouched(){
        assertEquals("\nAcme\tCloudBox  clodbox\n",PreferredSpelling.apply(
            "\nACME\tcloudbox  clodbox\n",Arrays.asList("Acme","CloudBox")));
    }
    @Test public void handlesEmptyVocabularyAndMalformedTerms(){
        assertEquals("ACME",PreferredSpelling.apply("ACME",null));
        assertEquals("ACME",PreferredSpelling.apply("ACME",Arrays.asList(null,""," ","heard => ")));
        assertEquals("",PreferredSpelling.apply("",Collections.singletonList("Acme")));
    }
    @Test public void contextualSpellingsOnlyApplyWithTheirAnchor(){
        java.util.List<String> terms=Arrays.asList("@when[Acme] box => Box","@when[Acme] CloudBox");
        assertEquals("Tie the box tightly.",PreferredSpelling.apply("Tie the box tightly.",terms));
        assertEquals("Acme needs a Box.",PreferredSpelling.apply("Acme needs a box.",terms));
        assertEquals("CLOUDBOX",PreferredSpelling.apply("CLOUDBOX",terms));
        assertEquals("Acme uses CloudBox",PreferredSpelling.apply("Acme uses CLOUDBOX",terms));
    }
    @Test public void malformedContextCannotBecomeAnUnscopedSpelling(){
        assertEquals("acme",PreferredSpelling.apply("acme",Collections.singletonList("@when[broken => Acme")));
    }
}
