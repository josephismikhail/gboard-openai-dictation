// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import java.util.Collection;
import java.util.regex.Pattern;

/** Conservative identification of supported Messages controls; unknown layouts stay untouched. */
public final class MessagesControlPolicy {
    public static final String MESSAGES_PACKAGE="com.google.android.apps.messaging";
    public static final String COMPOSER_VIEW_ID=MESSAGES_PACKAGE+":id/compose_message_text";
    public static final String RECORDER_VIEW_ID="Compose:Draft:Send";
    private static final String RECORDER_DESCRIPTION="Show record a voice message screen";
    private static final String OPEN_RECORDER_DESCRIPTION="Hide record a voice message screen";
    private static final Pattern SEND_DESCRIPTION=Pattern.compile("\\b(?:send|sending|sent)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);

    public static boolean isComposer(String packageName,String viewId,boolean editable,boolean enabled,boolean visible){
        return MESSAGES_PACKAGE.equals(packageName) && COMPOSER_VIEW_ID.equals(viewId)
            && editable && enabled && visible;
    }

    public static boolean isRecorder(String packageName,String viewId,boolean clickable,boolean enabled,
            boolean visible,Collection<String> descendantDescriptions){
        if(!MESSAGES_PACKAGE.equals(packageName) || !RECORDER_VIEW_ID.equals(viewId)
                || !clickable || !enabled || !visible || descendantDescriptions==null)return false;
        boolean recorder=false;
        for(String description:descendantDescriptions){
            if(description==null)continue;
            // Messages reuses this resource ID for Send. Conflicting semantics must win.
            if(SEND_DESCRIPTION.matcher(description).find())return false;
            if(OPEN_RECORDER_DESCRIPTION.equals(description) || RECORDER_DESCRIPTION.equals(description))recorder=true;
        }
        return recorder;
    }

    private MessagesControlPolicy(){}
}
