package com.avatarsize.lsposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * Enlarges the chat avatars (user + assistant) in RikkaHub.
 *
 * RikkaHub hard-codes both chat avatars to 28.dp in
 * me.rerere.rikkahub.ui.components.message.ChatMessageAvatarKt:
 *
 *     UIAvatar( modifier = Modifier.size(28.dp), ... )   // user
 *     UIAvatar( modifier = Modifier.size(28.dp), ... )   // assistant
 *
 * There is no in-app setting for it, so we hook the Compose sizing helper
 * androidx.compose.foundation.layout.SizeKt.size() and only rewrite the value
 * when the call originates from the chat-avatar composables. Everything else
 * (icons, buttons, spacing...) is left untouched.
 */
public class AvatarSizeHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "excp.rikkahub.debug";
    private static final String AVATAR_KT =
            "me.rerere.rikkahub.ui.components.message.ChatMessageAvatarKt";
    private static final String SIZE_KT =
            "androidx.compose.foundation.layout.SizeKt";

    /** Width/height RikkaHub hard-codes for the chat avatars. */
    private static final float OLD_SIZE = 28.0f;
    /** What we want instead (feel free to tweak). */
    private static final float NEW_SIZE = 56.0f;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        try {
            Class<?> sizeKt = XposedHelpers.findClass(SIZE_KT, lpparam.classLoader);
            int hooked = 0;
            for (Method m : sizeKt.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2
                        && "androidx.compose.ui.Modifier".equals(p[0].getName())
                        && p[1] == float.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Float f = (Float) param.args[1];
                                if (f != null && Math.abs(f - OLD_SIZE) < 0.05f
                                        && calledFromChatAvatar()) {
                                    param.args[1] = NEW_SIZE;
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                }
            }
            XposedBridge.log("[AvatarSize] hooked " + hooked + " size() method(s) in " + SIZE_KT);
        } catch (Throwable t) {
            XposedBridge.log("[AvatarSize] install failed: " + t);
        }
    }

    /** True only when the size() call is coming from the chat-avatar composables. */
    private static boolean calledFromChatAvatar() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cn = e.getClassName();
            if (AVATAR_KT.equals(cn) || cn.startsWith(AVATAR_KT + "$")) {
                return true;
            }
        }
        return false;
    }
}
