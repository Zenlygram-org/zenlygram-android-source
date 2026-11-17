/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.HashSet;

/**
 * One earlier version on the Edit History screen (iOS EditedMessageHistoryEntryNode): the version's date, its text
 * (formatting and emoji, plain links), then a photo / video preview (rounded, at most 280dp, play badge on videos),
 * or "file name · size", or "Media" for other media. Webpage previews are not shown. Read-only.
 */
public class ZgEditRevisionCell extends LinearLayout implements Theme.Colorable {

    private static final int MAX_PREVIEW_DP = 280;
    private static final int MIN_PREVIEW_DP = 60;
    /** Decode size of previews (ImageLoader "w_h" filter, in dp). */
    private static final String PREVIEW_FILTER = MAX_PREVIEW_DP + "_" + MAX_PREVIEW_DP;

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView dateView;
    private final TextView textView;
    private final FrameLayout previewLayout;
    private final BackupImageView imageView;
    private final PlayBadge playBadge;
    private final TextView fileView;

    private ZgHistoryStore.Revision revision;
    private int previewWidth;
    private int previewHeight;
    private boolean needDivider;

    public ZgEditRevisionCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setPadding(dp(16), dp(12), dp(16), dp(12));
        final int gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;

        dateView = new TextView(context);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        dateView.setGravity(gravity);
        addView(dateView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setGravity(gravity);
        textView.setLineSpacing(dp(2), 1.0f);
        addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        previewLayout = new FrameLayout(context);
        imageView = new BackupImageView(context);
        imageView.setRoundRadius(dp(10));
        previewLayout.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        playBadge = new PlayBadge(context);
        previewLayout.addView(playBadge, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        addView(previewLayout, LayoutHelper.createLinear(MIN_PREVIEW_DP, MIN_PREVIEW_DP, gravity, 0, 8, 0, 0));

        fileView = new TextView(context);
        fileView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        fileView.setTypeface(AndroidUtilities.bold());
        fileView.setGravity(gravity);
        fileView.setSingleLine(true);
        fileView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        addView(fileView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        updateColors();
    }

    /** The plain text of the version (what a long press copies). */
    public CharSequence getPlainText() {
        return revision != null && revision.message != null ? revision.message.message : null;
    }

    public void set(ZgHistoryStore.Revision revision, boolean divider) {
        this.revision = revision;
        this.needDivider = divider;
        final TLRPC.Message message = revision != null ? revision.message : null;
        dateView.setText(revision != null ? LocaleController.formatDateTime(revision.versionDate, true) : null);

        final CharSequence text = buildText(message);
        textView.setText(text);
        textView.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);

        bindMedia(message);
        requestLayout();
        invalidate();
    }

    private CharSequence buildText(TLRPC.Message message) {
        if (message == null || TextUtils.isEmpty(message.message)) {
            return null;
        }
        try {
            final SpannableStringBuilder builder = new SpannableStringBuilder(message.message);
            if (message.entities != null && !message.entities.isEmpty()) {
                MessageObject.addEntitiesToText(builder, message.entities, false, false, true, false);
            }
            return Emoji.replaceEmoji(builder, textView.getPaint().getFontMetricsInt(), false);
        } catch (Throwable e) {
            FileLog.e(e);
            return message.message;
        }
    }

    private void bindMedia(TLRPC.Message message) {
        previewWidth = 0;
        previewHeight = 0;
        previewLayout.setVisibility(GONE);
        playBadge.setVisibility(GONE);
        fileView.setVisibility(GONE);
        imageView.clearImage();
        final TLRPC.MessageMedia media = message != null ? message.media : null;
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
            return;
        }
        try {
            if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null && !(media.photo instanceof TLRPC.TL_photoEmpty)) {
                final TLRPC.Photo photo = media.photo;
                final TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
                final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 40);
                if (big == null) {
                    showFile(LocaleController.getString(R.string.ZG_History_EditHistory_Media));
                    return;
                }
                setPreviewSize(big.w, big.h);
                imageView.setImage(ImageLocation.getForPhoto(big, photo), PREVIEW_FILTER,
                    small != null && small != big ? ImageLocation.getForPhoto(small, photo) : null, small != null && small != big ? "b" : null,
                    0, message);
                previewLayout.setVisibility(VISIBLE);
            } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null && !(media.document instanceof TLRPC.TL_documentEmpty)) {
                final TLRPC.Document document = media.document;
                if (MessageObject.isVideoDocument(document) || MessageObject.isRoundVideoDocument(document) || MessageObject.isGifDocument(document)) {
                    int w = 0;
                    int h = 0;
                    for (int i = 0; i < document.attributes.size(); i++) {
                        final TLRPC.DocumentAttribute attribute = document.attributes.get(i);
                        if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                            w = attribute.w;
                            h = attribute.h;
                            break;
                        }
                    }
                    final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
                    final TLRPC.PhotoSize stripped = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 40);
                    if (w <= 0 || h <= 0) {
                        w = thumb != null ? thumb.w : 0;
                        h = thumb != null ? thumb.h : 0;
                    }
                    setPreviewSize(w, h);
                    imageView.setImage(thumb != null ? ImageLocation.getForDocument(thumb, document) : null, PREVIEW_FILTER,
                        stripped != null && stripped != thumb ? ImageLocation.getForDocument(stripped, document) : null, stripped != null && stripped != thumb ? "b" : null,
                        0, message);
                    previewLayout.setVisibility(VISIBLE);
                    playBadge.setVisibility(VISIBLE);
                } else {
                    String name = FileLoader.getDocumentFileName(document);
                    if (TextUtils.isEmpty(name)) {
                        name = LocaleController.getString(R.string.ZG_History_EditHistory_File);
                    }
                    if (document.size > 0) {
                        name = name + " · " + AndroidUtilities.formatFileSize(document.size);
                    }
                    showFile(name);
                }
            } else {
                showFile(LocaleController.getString(R.string.ZG_History_EditHistory_Media));
            }
        } catch (Throwable e) {
            FileLog.e(e);
            previewLayout.setVisibility(GONE);
            showFile(LocaleController.getString(R.string.ZG_History_EditHistory_Media));
        }
    }

    private void showFile(CharSequence text) {
        fileView.setText(text);
        fileView.setVisibility(VISIBLE);
    }

    private void setPreviewSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            w = 320;
            h = 240;
        }
        previewWidth = w;
        previewHeight = h;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        if (previewLayout.getVisibility() == VISIBLE && previewWidth > 0 && previewHeight > 0) {
            final float maxWidth = Math.max(dp(MIN_PREVIEW_DP), Math.min(width - getPaddingLeft() - getPaddingRight(), dp(MAX_PREVIEW_DP)));
            final float maxHeight = dp(MAX_PREVIEW_DP);
            final float scale = Math.min(maxWidth / previewWidth, maxHeight / previewHeight);
            final int w = Math.max(dp(MIN_PREVIEW_DP), Math.round(previewWidth * scale));
            final int h = Math.max(dp(MIN_PREVIEW_DP), Math.round(previewHeight * scale));
            final LayoutParams params = (LayoutParams) previewLayout.getLayoutParams();
            if (params.width != w || params.height != h) {
                params.width = w;
                params.height = h;
            }
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(Theme.key_paint_divider) : null;
            if (paint == null) {
                paint = Theme.dividerPaint;
            }
            if (paint != null) {
                final float y = getMeasuredHeight() - 1;
                final int inset = dp(16);
                canvas.drawLine(LocaleController.isRTL ? 0 : inset, y, getMeasuredWidth() - (LocaleController.isRTL ? inset : 0), y, paint);
            }
        }
    }

    @Override
    public void updateColors() {
        dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        fileView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
        invalidate();
    }

    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        colors.add(Theme.key_windowBackgroundWhiteGrayText);
        colors.add(Theme.key_windowBackgroundWhiteBlackText);
        colors.add(Theme.key_windowBackgroundWhiteLinkText);
        colors.add(Theme.key_windowBackgroundWhiteBlueText4);
    }

    /** 44dp half-black circle with a white play triangle (iOS generatePlayIcon). */
    private static final class PlayBadge extends View {
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();

        PlayBadge(Context context) {
            super(context);
            circlePaint.setColor(0x80000000);
            trianglePaint.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float size = Math.min(getWidth(), getHeight());
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy, size / 2f, circlePaint);
            final float unit = size / 44f;
            triangle.reset();
            triangle.moveTo(cx - 4f * unit, cy - 9f * unit);
            triangle.lineTo(cx + 11f * unit, cy);
            triangle.lineTo(cx - 4f * unit, cy + 9f * unit);
            triangle.close();
            canvas.drawPath(triangle, trianglePaint);
        }
    }

    /** UniversalAdapter factory; the revision is the item's object. */
    public static class Factory extends UItem.UItemFactory<ZgEditRevisionCell> {
        static {
            setup(new Factory());
        }

        @Override
        public ZgEditRevisionCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new ZgEditRevisionCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((ZgEditRevisionCell) view).set((ZgHistoryStore.Revision) item.object, item.checked);
        }

        public static UItem of(int id, ZgHistoryStore.Revision revision, boolean divider) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = revision;
            item.checked = divider;
            return item;
        }

        public static boolean is(UItem item) {
            return item != null && item.instanceOf(Factory.class);
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.id == b.id && a.object == b.object && a.checked == b.checked;
        }
    }
}
