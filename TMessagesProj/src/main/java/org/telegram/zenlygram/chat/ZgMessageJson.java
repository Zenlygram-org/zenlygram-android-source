/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chat;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.util.TypedValue;
import android.widget.TextView;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

/**
 * CONTEXT_MENU "JSON" (iOS SGShowMessageJson): the message as pretty-printed JSON in a dialog.
 *
 * <p>The JSON tree is built here by walking the public fields of the TL objects (like FileLog's TL serializer), not
 * by Gson reflection: only TL objects, strings, numbers, booleans, byte arrays, collections and maps are followed, so
 * no Android object (Context, Drawable, Bitmap, native buffers) is ever touched. Depth, node count and text size are
 * capped, and the work runs off the UI thread. Copy is offered only where Telegram allows copying.
 */
public final class ZgMessageJson {

    private static final int MAX_DEPTH = 24;
    private static final int MAX_NODES = 50_000;
    private static final int MAX_CHARS = 256 * 1024;

    /** Fields that are local helpers or back-references, not message data. */
    private static final HashSet<String> SKIPPED_FIELDS = new HashSet<>();

    static {
        SKIPPED_FIELDS.add("replyMessage");
        SKIPPED_FIELDS.add("networkType");
        SKIPPED_FIELDS.add("disableFree");
        SKIPPED_FIELDS.add("strippedBitmap");
        SKIPPED_FIELDS.add("mContext");
        SKIPPED_FIELDS.add("parentRichText");
        SKIPPED_FIELDS.add("parentBlock");
        SKIPPED_FIELDS.add("constructorName");
    }

    private ZgMessageJson() {
    }

    public static void show(ChatActivity chat, MessageObject message) {
        if (chat == null || message == null || message.messageOwner == null) {
            return;
        }
        final TLObject owner = message.messageOwner;
        final boolean canCopy = !chat.isPeerNoForwards() && !message.messageOwner.noforwards;
        Utilities.globalQueue.postRunnable(() -> {
            String json;
            try {
                json = toJson(owner);
            } catch (Throwable e) {
                FileLog.e(e);
                json = null;
            }
            final String result = json;
            AndroidUtilities.runOnUIThread(() -> showDialog(chat, result, canCopy));
        });
    }

    static String toJson(TLObject object) {
        final int[] budget = {MAX_NODES};
        final JsonElement tree = toElement(object, 0, budget);
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(tree);
        if (json.length() > MAX_CHARS) {
            json = json.substring(0, MAX_CHARS) + "\n…";
        }
        return json;
    }

    private static JsonElement toElement(Object value, int depth, int[] budget) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (--budget[0] < 0 || depth > MAX_DEPTH) {
            return new JsonPrimitive("…");
        }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Character) {
            return new JsonPrimitive((Character) value);
        }
        if (value instanceof byte[]) {
            return new JsonPrimitive(hex((byte[]) value));
        }
        if (value instanceof int[]) {
            final JsonArray array = new JsonArray();
            for (int v : (int[]) value) {
                array.add(v);
            }
            return array;
        }
        if (value instanceof long[]) {
            final JsonArray array = new JsonArray();
            for (long v : (long[]) value) {
                array.add(v);
            }
            return array;
        }
        if (value instanceof Collection) {
            final JsonArray array = new JsonArray();
            for (Object item : (Collection<?>) value) {
                array.add(toElement(item, depth + 1, budget));
            }
            return array;
        }
        if (value instanceof Map) {
            final JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                object.add(String.valueOf(entry.getKey()), toElement(entry.getValue(), depth + 1, budget));
            }
            return object;
        }
        if (value instanceof TLObject) {
            final JsonObject object = new JsonObject();
            String className = value.getClass().getName();
            final String prefix = "org.telegram.tgnet.";
            if (className.startsWith(prefix)) {
                className = className.substring(prefix.length());
            }
            object.addProperty("_", className);
            for (Field field : value.getClass().getFields()) {
                final int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || SKIPPED_FIELDS.contains(field.getName())) {
                    continue;
                }
                final Object fieldValue;
                try {
                    fieldValue = field.get(value);
                } catch (Throwable e) {
                    continue;
                }
                if (fieldValue == null || isPlainOrTl(fieldValue)) {
                    object.add(field.getName(), toElement(fieldValue, depth + 1, budget));
                }
            }
            return object;
        }
        // Anything else (Android objects, local helpers) is left out.
        return new JsonPrimitive(value.getClass().getSimpleName());
    }

    private static boolean isPlainOrTl(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character
            || value instanceof byte[] || value instanceof int[] || value instanceof long[]
            || value instanceof Collection || value instanceof Map || value instanceof TLObject;
    }

    private static String hex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        final int n = Math.min(bytes.length, 4096);
        final StringBuilder sb = new StringBuilder(2 + n * 2 + 1);
        sb.append("0x");
        for (int i = 0; i < n; i++) {
            sb.append(digits[(bytes[i] >> 4) & 0xF]).append(digits[bytes[i] & 0xF]);
        }
        if (n < bytes.length) {
            sb.append('…');
        }
        return sb.toString();
    }

    private static void showDialog(ChatActivity chat, String json, boolean canCopy) {
        final Activity activity = chat.getParentActivity();
        if (activity == null || chat.isFinished || chat.isRemovingFromStack()) {
            return;
        }
        if (json == null) {
            BulletinFactory.of(chat).createErrorBulletin(getString(R.string.ZG_InputStickers_Json_Error)).show();
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = chat.getResourceProvider();
        final TextView textView = new TextView(activity);
        textView.setText(json);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        textView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        textView.setTextIsSelectable(canCopy);
        textView.setPadding(dp(24), dp(4), dp(24), dp(12));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle("JSON");
        builder.setView(textView); // AlertDialog already puts its custom view into a ScrollView
        if (canCopy) {
            builder.setPositiveButton(getString(R.string.Copy), (dialog, which) -> {
                if (AndroidUtilities.addToClipboard(json) && !chat.isFinished) {
                    BulletinFactory.of(chat).createCopyBulletin(getString(R.string.ZG_InputStickers_Json_Copied)).show();
                }
            });
            builder.setNegativeButton(getString(R.string.Close), null);
        } else {
            builder.setPositiveButton(getString(R.string.Close), null);
        }
        chat.showDialog(builder.create());
    }
}
