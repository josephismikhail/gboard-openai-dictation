package org.gboardopenai.test;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.view.accessibility.AccessibilityManager;
import java.util.Arrays;

/** Verifies opt-in service metadata without enabling it or reading conversations. */
public final class MessagesIntegrationChecks {
    public static org.json.JSONObject inspect()throws Exception{
        java.lang.reflect.Field active=org.gboardopenai.MessagesAccessibilityService.class.getDeclaredField("instance");active.setAccessible(true);
        Object service=active.get(null);org.json.JSONObject result=new org.json.JSONObject().put("connected",service!=null);
        if(service!=null){
            for(String name:new String[]{"recorderAvailable","handingOff"}){
                java.lang.reflect.Field field=service.getClass().getDeclaredField(name);field.setAccessible(true);result.put(name,field.getBoolean(service));
            }
            java.lang.reflect.Field viewField=service.getClass().getDeclaredField("shortcut");viewField.setAccessible(true);
            android.view.View view=(android.view.View)viewField.get(service);result.put("overlayVisible",view!=null);
            if(view!=null){android.graphics.Rect bounds=new android.graphics.Rect();view.getGlobalVisibleRect(bounds);result.put("bounds",bounds.toShortString());}
        }
        return result;
    }
    public static void run(Context context)throws Exception{
        ComponentName name=new ComponentName(context,"org.gboardopenai.MessagesAccessibilityService");
        ServiceInfo component=context.getPackageManager().getServiceInfo(name,0);
        if(!"android.permission.BIND_ACCESSIBILITY_SERVICE".equals(component.permission))
            throw new AssertionError("Service binding is not protected");
        AccessibilityManager manager=context.getSystemService(AccessibilityManager.class);
        AccessibilityServiceInfo found=null;
        for(AccessibilityServiceInfo service:manager.getInstalledAccessibilityServiceList())
            if(name.equals(ComponentName.unflattenFromString(service.getId())))found=service;
        if(found==null)throw new AssertionError("Android did not discover the Messages service");
        if((found.getCapabilities()&AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)==0)
            throw new AssertionError("Service metadata XML did not resolve");
        if(found.packageNames==null || found.packageNames.length!=1 || !Arrays.asList(found.packageNames).contains("com.google.android.apps.messaging"))
            throw new AssertionError("Service event scope is not Messages-only");
        if((found.flags&AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)==0 || (found.flags&AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)==0)
            throw new AssertionError("Required view/window metadata flags are missing");
        CharSequence description=found.loadDescription(context.getPackageManager());
        if(description==null || !description.toString().contains("Google Messages"))throw new AssertionError("Disclosure resource did not resolve");
    }
}
