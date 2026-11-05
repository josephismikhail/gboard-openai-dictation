// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import com.trevornk.ramblr.VocabularyAliases;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restores the exact case of configured vocabulary, without guessing heard words. */
public final class PreferredSpelling {
    private static final String WORD="[\\p{L}\\p{M}\\p{N}_]";

    public static String apply(String text,List<String> vocabulary){
        if(text==null || text.isEmpty() || vocabulary==null || vocabulary.isEmpty())return text;
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        List<String> nonNullTerms=new ArrayList<>();
        for(String term:vocabulary)if(term!=null)nonNullTerms.add(term);
        for(String term:VocabularyAliases.INSTANCE.activeTerms(text,nonNullTerms)){
            String canonical=VocabularyAliases.INSTANCE.canonical(term);
            if(!canonical.isEmpty())
                unique.putIfAbsent(canonical.toLowerCase(Locale.ROOT),canonical);
        }
        List<String> words=new ArrayList<>(unique.values());
        words.sort((a,b)->Integer.compare(b.length(),a.length()));
        if(words.isEmpty())return text;
        StringBuilder expression=new StringBuilder("(?<!"+WORD+")(?:");
        for(int i=0;i<words.size();i++){
            if(i>0)expression.append('|');
            expression.append('(').append(Pattern.quote(words.get(i))).append(')');
        }
        expression.append(")(?!").append(WORD).append(')');
        Matcher matcher=Pattern.compile(expression.toString(),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text);
        StringBuffer result=new StringBuffer(text.length());
        while(matcher.find()){
            for(int i=0;i<words.size();i++)if(matcher.group(i+1)!=null){
                matcher.appendReplacement(result,Matcher.quoteReplacement(words.get(i)));break;
            }
        }
        matcher.appendTail(result);return result.toString();
    }
    private PreferredSpelling(){}
}
