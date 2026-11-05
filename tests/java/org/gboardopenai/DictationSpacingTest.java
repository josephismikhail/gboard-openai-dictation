package org.gboardopenai;

import org.junit.Test;
import static org.junit.Assert.*;

public class DictationSpacingTest {
    private void check(String expected, int cursor, String text, String before, String after) {
        DictationSpacing.Insertion result=DictationSpacing.prepare(text,before,after);
        assertEquals(expected,result.text);assertEquals(cursor,result.cursorPosition);
    }
    @Test public void firstDictationHasNoTrailingSpace() { check("Hello there.",1,"Hello there.","",""); }
    @Test public void secondDictationAddsOnlyItsLeadingSeparator() { check(" How are you?",1,"How are you?","e.",""); }
    @Test public void existingUnspacedTextGetsSeparator() { check(" More words.",1,"More words.","s.",""); }
    @Test public void insertionBetweenWordsGetsBothBoundaries() { check(" new ",1,"new","ft","ri"); }
    @Test public void existingSpaceIsReusedAndCaretMovesPastIt() { check(" new",2,"new","ft"," r"); }
    @Test public void punctuationOnRightHasNoNewSpaceBeforeIt() { check("word",1,"word"," ",", "); }
    @Test public void dictatedPunctuationAttachesToPreviousText() { check(", please.",1,", please.","lo",""); }
    @Test public void parenthesesStayAttached() { check("hello",1,"hello","(",")"); }
    @Test public void openingDelimiterKeepsNextDictationAttached() {
        for(String opener:new String[]{"(","[","{","\u201c","\u2018"}){
            checkOpeningContinuation(opener);
        }
    }
    private void checkOpeningContinuation(String opener) {
        DictationSpacing.Insertion first=DictationSpacing.prepare(opener,"","");
        assertEquals(opener,first.text);
        DictationSpacing.Insertion next=DictationSpacing.prepare("hello",first.text,"");
        assertEquals(opener+"hello",first.text+next.text);
    }
    @Test public void paragraphBreaksAreNotChangedOrSkipped() { check("Next line",1,"Next line","\n","\n"); }
    @Test public void formattedListsArePreserved() { check("1. First\n2. Second",1,"1. First\n2. Second","",""); }
    @Test public void existingWhitespaceAndNonbreakingSpacesAreNotDuplicated() { check("hello",1,"hello","\u00a0","\u00a0"); }
    @Test public void quotesUseAdjacentContext() {
        check("hello",1,"hello"," \"","\" ");
        check(" then",1,"then","o\"","");
    }
    @Test public void possessiveSuffixStaysAttachedToReplacementName() {
        check("Jane",1,"Jane","","'s");
        check("Jane",1,"Jane","","\u2019s");
    }
    @Test public void contractionSuffixStaysAttached() {
        check("won",1,"won","","'t");
        check("we",1,"we","","'r");
        check("say ",1,"say","","\"h");
    }
    @Test public void unknownContextDoesNotInventBoundaries() { check("hello",1,"hello",null,null); }
    @Test public void emptyTranscriptDoesNotInsertOrMoveCursor() { check("",1,"","t"," "); }
}
