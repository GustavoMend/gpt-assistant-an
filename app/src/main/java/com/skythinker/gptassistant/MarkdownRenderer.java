package com.skythinker.gptassistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.commonmark.node.FencedCodeBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonSpansFactory;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.tables.TableAwareMovementMethod;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.image.ImageSize;
import io.noties.markwon.image.ImageSizeResolverDef;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;
import io.noties.markwon.movement.MovementMethodPlugin;
import io.noties.markwon.syntax.Prism4jThemeDefault;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.markwon.utils.LeadingMarginUtils;
import io.noties.prism4j.Prism4j;

public class MarkdownRenderer {
    private final Context context;
    private final Markwon markwon;

    // New CodeBlockSpan to identify code blocks for long-press action
    class CodeBlockSpan {
    }

    public MarkdownRenderer(Context context) {
        this.context = context;
        markwon = Markwon.builder(context)
                .usePlugin(SyntaxHighlightPlugin.create(new Prism4j(new GrammarLocatorDef()), Prism4jThemeDefault.create(0)))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureSpansFactory(@NonNull MarkwonSpansFactory.Builder builder) {
                        // Use CodeBlockSpan to identify code blocks
                        builder.appendFactory(FencedCodeBlock.class, (configuration, props) -> new CodeBlockSpan());
                        // You can uncomment the line below if you want to display the copy icon
                        // builder.appendFactory(FencedCodeBlock.class, (configuration, props) -> new CopyIconSpan());
                    }
                })
                .usePlugin(JLatexMathPlugin.create(40, builder -> builder.inlinesEnabled(true)))
                .usePlugin(ImagesPlugin.create())
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @NonNull
                    @Override
                    public String processMarkdown(@NonNull String markdown) { // Preprocess Markdown text
                        List<String> sepList = new ArrayList<>(Arrays.asList(markdown.split("```", -1)));
                        for (int i = 0; i < sepList.size(); i += 2) { // Skip code blocks to avoid altering code content
                            // Replace single $ with double $$ for LaTeX rendering
                            String regexDollar = "(?<!\\$)\\$(?!\\$)([^\\n]*?)(?<!\\$)\\$(?!\\$)";
                            String regexBrackets = "(?s)\\\\\\[(.*?)\\\\\\]";
                            String regexParentheses = "\\\\\\(([^\\n]*?)\\\\\\)";
                            String latexReplacement = "\\$\\$$1\\$\\$";
                            // Add link to images
                            String regexImage = "!\\[(.*?)\\]\\((.*?)\\)";
                            String imageReplacement = "[$0]($2)";
                            // Perform replacements
                            sepList.set(i, sepList.get(i).replaceAll(regexDollar, latexReplacement)
                                    .replaceAll(regexBrackets, latexReplacement)
                                    .replaceAll(regexParentheses, latexReplacement)
                                    .replaceAll(regexImage, imageReplacement));
                        }
                        return String.join("```", sepList);
                    }
                })
                .usePlugin(new AbstractMarkwonPlugin() { // Set image size limits
                    @Override
                    public void configureConfiguration(@NonNull MarkwonConfiguration.Builder builder) {
                        builder.imageSizeResolver(new ImageSizeResolverDef() {
                            @NonNull
                            @Override
                            protected Rect resolveImageSize(@Nullable ImageSize imageSize, @NonNull Rect imageBounds, int canvasWidth, float textSize) {
                                int maxSize = GlobalUtils.dpToPx(context, 120);
                                if (imageBounds.width() > maxSize || imageBounds.height() > maxSize) {
                                    float ratio = Math.min((float) maxSize / imageBounds.width(), (float) maxSize / imageBounds.height());
                                    imageBounds.right = imageBounds.left + (int) (imageBounds.width() * ratio);
                                    imageBounds.bottom = imageBounds.top + (int) (imageBounds.height() * ratio);
                                }
                                return imageBounds;
                            }
                        });
                    }
                })
                // .usePlugin(TablePlugin.create(context)) // Uncomment if you plan to use tables
                // .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
                .build();
    }

    public void render(TextView textView, String markdown) {
        if (textView != null && markdown != null) {
            try {
                // Set custom MovementMethod to handle long-press events
                textView.setMovementMethod(new LongPressMovementMethod());
                markwon.setMarkdown(textView, markdown);
                // Uncomment for debugging
                // Log.d("MarkdownRenderer", "render: " + markdown);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Custom MovementMethod to handle long-press on code blocks
    private class LongPressMovementMethod extends LinkMovementMethod {

        private GestureDetector gestureDetector;

        public LongPressMovementMethod() {
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent e) {
                    // Handle long-press event
                    if (gestureDetectorView instanceof TextView) {
                        TextView widget = (TextView) gestureDetectorView;
                        Spannable buffer = (Spannable) widget.getText();

                        int x = (int) e.getX();
                        int y = (int) e.getY();

                        x -= widget.getTotalPaddingLeft();
                        y -= widget.getTotalPaddingTop();

                        x += widget.getScrollX();
                        y += widget.getScrollY();

                        Layout layout = widget.getLayout();
                        int line = layout.getLineForVertical(y);
                        int off = layout.getOffsetForHorizontal(line, x);

                        // Look for CodeBlockSpan at the pressed position
                        CodeBlockSpan[] codeBlockSpans = buffer.getSpans(off, off, CodeBlockSpan.class);

                        if (codeBlockSpans.length != 0) {
                            int start = buffer.getSpanStart(codeBlockSpans[0]);
                            int end = buffer.getSpanEnd(codeBlockSpans[0]);
                            String text = buffer.subSequence(start, end).toString().trim();
                            // Copy the code block text to clipboard
                            GlobalUtils.copyToClipboard(context, text);
                            // Show a toast notifications
                            //GlobalUtils.showToast(context, context.getString(R.string.toast_code_clipboard), false);
                        }
                    }
                }
            });
        }

        private View gestureDetectorView;

        @Override
        public boolean onTouchEvent(@NonNull TextView widget, @NonNull Spannable buffer, @NonNull MotionEvent event) {
            gestureDetectorView = widget;
            gestureDetector.onTouchEvent(event);
            return super.onTouchEvent(widget, buffer, event);
        }
    }
}
