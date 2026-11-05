// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import com.trevornk.ramblr.VocabularyAliases;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Merges exact preferred spellings without removing existing mishearing rules. */
public final class PreferredVocabulary {
    private static final Pattern SCOPE=Pattern.compile("^(@when\\[[^]\\r\\n]+]\\s+)(.+)$");
    public static List<String> merge(List<String> existing,List<String> additions){
        if(additions.size()>128)throw new IllegalArgumentException("Too many spellings");
        List<String> terms=new ArrayList<>(existing);
        for(String addition:additions){
            String desired=addition.trim();
            if(desired.isEmpty() || desired.length()>128 || desired.contains("=>") || desired.startsWith("@when["))
                throw new IllegalArgumentException("Expected a plain preferred spelling");
            List<String> merged=new ArrayList<>();
            boolean plainPresent=false;
            for(String term:terms){
                Matcher scoped=SCOPE.matcher(term.trim());
                boolean isScoped=scoped.matches();
                String prefix=isScoped?scoped.group(1):"";
                String rule=isScoped?scoped.group(2):term;
                if(!VocabularyAliases.INSTANCE.canonical(rule).equalsIgnoreCase(desired)){
                    merged.add(term);continue;
                }
                int arrow=rule.indexOf("=>");
                if(arrow>=0){
                    // Keep the heard phrase and any contextual scope intact.
                    merged.add(prefix+rule.substring(0,arrow+2)+" "+desired);
                }else if(isScoped){
                    merged.add(prefix+desired);
                }else if(!plainPresent){
                    merged.add(desired);plainPresent=true;
                }
            }
            if(!plainPresent)merged.add(desired);
            terms=merged;
        }
        return terms;
    }
    private PreferredVocabulary(){}
}
