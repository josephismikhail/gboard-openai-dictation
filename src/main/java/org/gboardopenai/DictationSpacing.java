// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** Prose boundary spacing only; never rewrites the dictated text or surrounding editor content. */
public final class DictationSpacing {
    public static final class Insertion {
        public final String text;
        public final int cursorPosition;
        Insertion(String text, int cursorPosition) { this.text=text; this.cursorPosition=cursorPosition; }
    }
    public static Insertion prepare(String text, CharSequence before, CharSequence after) {
        if (text.isEmpty() || text.codePoints().allMatch(DictationSpacing::space)) return new Insertion(text,1);
        int first=text.codePointAt(0), last=text.codePointBefore(text.length());
        boolean prefix=false, suffix=false;
        int cursor=1;
        if (before!=null && before.length()>0) {
            int left=Character.codePointBefore(before,before.length());
            boolean opening=opens(left);
            if (left=='"' || left=='\'') {
                int previous=before.length()>1?Character.codePointBefore(before,before.length()-1):-1;
                opening=previous<0 || space(previous) || opens(previous);
            }
            prefix=!space(left) && !space(first) && !opening && !closes(first);
        }
        if (after!=null && !space(last)) {
            if (after.length()==0) suffix=!opens(last);
            else {
                int right=Character.codePointAt(after,0);
                boolean closing=closes(right);
                if (right=='"' || right=='\'') {
                    int next=after.length()>1?Character.codePointAt(after,1):-1;
                    // A straight apostrophe between word characters joins a possessive/contraction.
                    // Treat it like its curly counterpart, rather than as an opening quotation.
                    boolean joinsWord=right=='\'' && Character.isLetterOrDigit(last) && Character.isLetterOrDigit(next);
                    closing=next<0 || space(next) || closes(next) || joinsWord;
                }
                suffix=!space(right) && !closing && !opens(last);
                // Reuse an existing ordinary space and leave the caret ready to continue.
                if (right==' ') cursor=2;
            }
        }
        return new Insertion((prefix?" ":"")+text+(suffix?" ":""),cursor);
    }
    private static boolean space(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
    private static boolean opens(int c) { return "([{\u201c\u2018".indexOf(c)>=0; }
    private static boolean closes(int c) { return ",.!?;:%)]}\u2026\u201d\u2019".indexOf(c)>=0; }
    private DictationSpacing() {}
}
