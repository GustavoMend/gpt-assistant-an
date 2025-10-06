package com.skythinker.gptassistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.LinkResolver;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonSpansFactory;
import io.noties.markwon.core.spans.LinkSpan;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.tables.TableAwareMovementMethod;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.image.ImageProps;
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

    class CopyableCodeSpan implements LeadingMarginSpan {
        @Override
        public int getLeadingMargin(boolean first) { 
            return 0; 
        }

        @Override
        public void drawLeadingMargin(@NonNull Canvas canvas, @NonNull Paint p, int x, int dir, 
                                     int top, int baseline, int bottom, @NonNull CharSequence text, 
                                     int start, int end, boolean first, @NonNull Layout layout) {
            if (!LeadingMarginUtils.selfStart(start, text, this)) return;

            int save = canvas.save();
            try {
                Paint paint = new Paint();
                String textToDraw = context.getString(R.string.text_copy_code_notice);
                paint.setTextSize(GlobalUtils.dpToPx(context, 14));
                paint.setColor(0x50000000);
                Rect bounds = new Rect();
                paint.getTextBounds(textToDraw, 0, textToDraw.length(), bounds);
                int y = top + bounds.height() + GlobalUtils.dpToPx(context, 4);
                int x1 = layout.getWidth() - bounds.width() - GlobalUtils.dpToPx(context, 8);
                if(layout.getWidth() > bounds.width() + GlobalUtils.dpToPx(context, 16))
                    canvas.drawText(textToDraw, x1, y, paint);
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    class ImageLinkResolver implements LinkResolver {
        private LinkResolver original;

        public ImageLinkResolver(LinkResolver original) {
            this.original = original;
        }

        @Override
        public void resolve(@NonNull View view, @NonNull String link) {
            Log.d("markdown", "Image resolver link = " + link);
            original.resolve(view, link);
        }
    }

    public MarkdownRenderer(Context context) {
        this.context = context;
        markwon = Markwon.builder(context)
                .usePlugin(SyntaxHighlightPlugin.create(new Prism4j(new GrammarLocatorDef()), Prism4jThemeDefault.create(0)))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureSpansFactory(@NonNull MarkwonSpansFactory.Builder builder) {
                        builder.appendFactory(FencedCodeBlock.class, (configuration, props) -> new CopyableCodeSpan());
                    }
                })
                .usePlugin(JLatexMathPlugin.create(40, builder -> builder.inlinesEnabled(true)))
                .usePlugin(ImagesPlugin.create())
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @NonNull
                    @Override
                    public String processMarkdown(@NonNull String markdown) {
                        List<String> sepList = new ArrayList<>(Arrays.asList(markdown.split("```", -1)));
                        for (int i = 0; i < sepList.size(); i += 2) {
                            String regexDollar = "(?<!\\$$)\\$$(?!\\$$)([^\\n]*?)(?<!\\$$)\\$$(?!\\$$)";
                            String regexBrackets = "(?s)\\\\\$$(.*?)\\\\\$$";
                            String regexParentheses = "\\\\\$$([^\\n]*?)\\\\\$$";
                            String latexReplacement = "\\$$\\$$1\\$$\\$";
                            String regexImage = "!\$$(.*?)\$$\$$(.*?)\$$";
                            String imageReplacement = "[$$0]($$2)";
                            String regexThinkComplete = "(?s)^<think>\\n(.*?)\\n</think>\\n";
                            String thinkCompleteReplacement = "```text\n" + context.getString(R.string.text_think_header) + "\n\n$1\n```\n";
                            String regexThinkStart = "(?s)^<think>\\n(.*?)$";
                            String thinkStartReplacement = "```text\n" + context.getString(R.string.text_thinking_header) + "\n\n$1\n```\n";
                            sepList.set(i, sepList.get(i).replaceAll(regexDollar, latexReplacement)
                                    .replaceAll(regexBrackets, latexReplacement)
                                    .replaceAll(regexParentheses, latexReplacement)
                                    .replaceAll(regexImage, imageReplacement)
                                    .replaceAll(regexThinkComplete, thinkCompleteReplacement)
                                    .replaceAll(regexThinkStart, thinkStartReplacement));
                        }
                        return String.join("```", sepList);
                    }
                })
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureConfiguration(@NonNull MarkwonConfiguration.Builder builder) {
                        builder.imageSizeResolver(new ImageSizeResolverDef(){
                            @NonNull @Override
                            protected Rect resolveImageSize(@Nullable ImageSize imageSize, @NonNull Rect imageBounds, int canvasWidth, float textSize) {
                                int maxSize = GlobalUtils.dpToPx(context, 120);
                                if(imageBounds.width() > maxSize || imageBounds.height() > maxSize) {
                                    float ratio = Math.min((float)maxSize / imageBounds.width(), (float)maxSize / imageBounds.height());
                                    imageBounds.right = imageBounds.left + (int)(imageBounds.width() * ratio);
                                    imageBounds.bottom = imageBounds.top + (int)(imageBounds.height() * ratio);
                                }
                                return imageBounds;
                            }
                        });
                    }
                })
                .usePlugin(TablePlugin.create(context))
                .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
                .build();
    }

    public void render(TextView textView, String markdown) {
        if(textView != null && markdown != null) {
            try {
                markwon.setMarkdown(textView, markdown);
                setupCopyOnClick(textView);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void setupCopyOnClick(TextView textView) {
        textView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (handleCopyButtonClick(textView, event)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private boolean handleCopyButtonClick(TextView textView, MotionEvent event) {
        Spanned spanned = (Spanned) textView.getText();
        Layout layout = textView.getLayout();
        if (layout == null) return false;

        int x = (int) event.getX();
        int y = (int) event.getY();
        
        int line = layout.getLineForVertical(y);
        int offset = layout.getOffsetForHorizontal(line, x);

        CopyableCodeSpan[] spans = spanned.getSpans(offset, offset, CopyableCodeSpan.class);
        if (spans.length > 0) {
            int spanStart = spanned.getSpanStart(spans[0]);
            int spanEnd = spanned.getSpanEnd(spans[0]);
            int spanStartLine = layout.getLineForOffset(spanStart);

            // Define copy button area (top-right corner of code block)
            int padding = GlobalUtils.dpToPx(context, 8);
            int buttonWidth = GlobalUtils.dpToPx(context, 80);
            int buttonHeight = GlobalUtils.dpToPx(context, 40);
            
            int lineTop = layout.getLineTop(spanStartLine);
            int layoutWidth = layout.getWidth();

            boolean inButtonArea = x > (layoutWidth - buttonWidth - padding) &&
                                   y >= lineTop && 
                                   y <= (lineTop + buttonHeight);

            if (inButtonArea) {
                String code = spanned.subSequence(spanStart, spanEnd).toString()
                    .replaceAll("^\\u00A0+", "").replaceAll("\\u00A0+$", "").trim();
                GlobalUtils.copyToClipboard(context, code);
                GlobalUtils.showToast(context, context.getString(R.string.toast_code_clipboard), false);
                return true;
            }
        }
        return false;
    }
}
