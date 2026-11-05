package org.gboardopenai;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreferredVocabularyTest {
    @Test public void preservesAliasesAndScopesWhileUpdatingTargetCase(){
        assertEquals(Arrays.asList("akme => Acme","@when[work] akmi => Acme","CloudBox","Acme"),
            PreferredVocabulary.merge(Arrays.asList("akme => ACME","@when[work] akmi => acme","CloudBox"),Collections.singletonList("Acme")));
    }
    @Test public void deduplicatesPlainEntriesWithoutRemovingAliasRules(){
        assertEquals(Arrays.asList("Acme","akme => Acme","CloudBox"),
            PreferredVocabulary.merge(Arrays.asList("ACME","akme => ACME","acme","CloudBox"),Collections.singletonList("Acme")));
    }
    @Test public void keepsScopesAndLeavesUnrelatedRulesIntact(){
        java.util.List<String> merged=PreferredVocabulary.merge(
            Arrays.asList("@when[work] acme","@when[home] box => CloudBox"),Collections.singletonList("Acme"));
        assertEquals(Arrays.asList("@when[work] Acme","@when[home] box => CloudBox","Acme"),merged);
        assertEquals("work at Acme",PreferredSpelling.apply("work at ACME",merged));
    }
    @Test public void repeatedMergeIsIdempotentAndDoesNotMutateInputs(){
        java.util.List<String> existing=Arrays.asList("akme => ACME","Acme");
        java.util.List<String> merged=PreferredVocabulary.merge(existing,Collections.singletonList("Acme"));
        assertEquals(merged,PreferredVocabulary.merge(merged,Collections.singletonList("Acme")));
        assertEquals(Arrays.asList("akme => ACME","Acme"),existing);
    }
    @Test public void rejectsRuleSyntaxInPlainSpellingAdditions(){
        for(String invalid:Arrays.asList(" ","heard => Acme","@when[work] Acme")){
            try{PreferredVocabulary.merge(Collections.emptyList(),Collections.singletonList(invalid));fail("accepted invalid spelling");}
            catch(IllegalArgumentException expected){}
        }
    }
}
