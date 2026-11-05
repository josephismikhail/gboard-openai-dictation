package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

class VocabularyAliasesTest {
    @Test fun aContextualNameDoesNotReplaceLowercaseOrdinaryEnglish() {
        val terms = listOf("@when[Rovers] Solace => Solin")
        assertEquals("I find solace in Rovers wins.", VocabularyAliases.correct("I find solace in Rovers wins.", terms))
        assertEquals("Solin played for Rovers.", VocabularyAliases.correct("Solace played for Rovers.", terms))
    }
    @Test fun shortNameAliasesRequireTheirContext() {
        val terms = listOf("@when[Seabrook|Rovers] Nina => Nena", "@when[Seabrook|Rovers] Marces => Marques")
        assertEquals("Nena and Marques play for Rovers.", VocabularyAliases.correct("Nina and Marces play for Rovers.", terms))
        assertEquals("Nina and Marcos called me.", VocabularyAliases.correct("Nina and Marcos called me.", terms))
        assertEquals("Nina called me.", VocabularyPostCorrector.correct("Nina called me.", terms))
    }
    @Test fun contextualSingleWordAliasesDoNotFuzzOrdinaryWords() {
        val terms = listOf("@when[Rovers] Solis => Solin", "@when[Rovers] Marces => Marques")
        for (text in listOf("I think Rovers will have such a solid season.",
            "Rovers are solid.", "Solid season for Rovers.", "Marcos plays for Rovers.")) {
            assertEquals(text, VocabularyAliases.correct(text, terms))
            assertEquals(text, VocabularyPostCorrector.correct(text, terms))
        }
        assertEquals("Solin plays for Rovers.", VocabularyAliases.correct("Solis plays for Rovers.", terms))
    }
    @Test fun contextualAnchorsRespectWordBoundaries() {
        val terms = listOf("@when[Rovers] Nina => Nena")
        assertEquals("Nina likes crossovers.", VocabularyAliases.correct("Nina likes crossovers.", terms))
    }
    @Test fun aShortContextualAliasDoesNotBlockFullNameCorrection() {
        val terms = listOf("Nina Merces => Nena Marques", "@when[Rovers] Nina => Nena")
        assertEquals("Nena Marques plays for Rovers.",
            VocabularyAliases.correct("Nina Mercas plays for Rovers.", terms))
    }
    @Test fun matchesUnseenPhoneticVariantsOfPrivateNames() {
        val terms = listOf("Ruben Medweke => Ruben Moduke")
        assertEquals("Ruben Moduke", VocabularyAliases.correct("Ruben Madweque", terms))
        assertEquals("Ruben Moduke", VocabularyAliases.correct("Ruban Matweke", terms))
        assertEquals("Ruben Moduke", VocabularyAliases.correct("Ruban Moduke", terms))
    }
    @Test fun phoneticCollisionsAreLeftUnchanged() {
        val text = "Ruben Medwique"
        assertEquals(text, VocabularyAliases.correct(text,
            listOf("Ruben Medweke => Person One", "Ruben Madwike => Person Two")))
    }
    @Test fun toleratesOneLetterVariationInAnExplicitMultiWordName() {
        assertEquals("Ask Ruben Marques, please.", VocabularyAliases.correct("Ask Ruben Marcas, please.",
            listOf("Ruben Marces => Ruben Marques")))
    }
    @Test fun ambiguousNearAliasesAreNotApplied() {
        val text = "Ask Ruben Marcos."
        assertEquals(text, VocabularyAliases.correct(text,
            listOf("Ruben Marces => Person One", "Ruben Marcas => Person Two")))
    }
    @Test fun neverFuzzesIdsCommonPhrasesOrAcrossSentences() {
        assertEquals("smart task", VocabularyAliases.correct("smart task", listOf("start task => StartTask")))
        assertEquals("Ruben. Marcas", VocabularyAliases.correct("Ruben. Marcas", listOf("Ruben Marces => Ruben Marques")))
        assertEquals("service 124", VocabularyAliases.correct("service 124", listOf("service 123 => Service123")))
    }
    @Test fun aCorrectCanonicalNameIsProtectedFromFuzzyAliases() {
        assertEquals("Ruben Marcas", VocabularyAliases.correct("Ruben Marcas",
            listOf("Ruben Marcas", "Ruben Marces => Someone Else")))
    }
    @Test fun correctsExplicitPhrasesAndPreservesSurroundingPunctuation() {
        assertEquals("I use Acme Tools, daily.", VocabularyAliases.correct("I use ack me tools, daily.", listOf("ack me tools => Acme Tools")))
    }
    @Test fun longerAliasWinsAndDoesNotCascade() {
        assertEquals("Acme", VocabularyAliases.correct("ack me'", listOf("ack me => Acme", "ack me' => Acme", "Acme => Other")))
    }
    @Test fun respectsWholeWordBoundaries() {
        assertEquals("tracking", VocabularyAliases.correct("tracking", listOf("ack => Acme")))
    }
}
