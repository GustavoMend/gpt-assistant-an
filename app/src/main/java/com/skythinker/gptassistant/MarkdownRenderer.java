package com.skythinker.gptassistant;

import android.content.Context;
import android.graphics.Color;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.commonmark.node.FencedCodeBlock;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;
import io.noties.markwon.syntax.Prism4jThemeDefault;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.prism4j.Prism4j;

public class MarkdownRenderer {
    private final Context context;
    private final Markwon markwon;

    public MarkdownRenderer(Context context) {
        this.context = context;
        markwon = Markwon.builder(context)
                // Syntax highlighting plugin
                .usePlugin(SyntaxHighlightPlugin.create(
                        new Prism4j(new GrammarLocatorDef()),
                        Prism4jThemeDefault.create(Color.TRANSPARENT))
                )
                // Custom plugin to handle FencedCodeBlock nodes
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
                        builder.on(FencedCodeBlock.class, (visitor, fencedCodeBlock) -> {
                            // Retrieve the code content and language
                            String codeContent = fencedCodeBlock.getLiteral();
                            String info = fencedCodeBlock.getInfo();

                            // Apply syntax highlighting
                            SyntaxHighlightPlugin plugin = visitor.configuration().syntaxHighlightPlugin();
                            CharSequence highlightedCode = plugin.highlight(info, codeContent);

                            SpannableStringBuilder codeBuilder = new SpannableStringBuilder();
                            codeBuilder.append(highlightedCode);

                            // Ensure the code ends with a newline
                            if (!codeBuilder.toString().endsWith("\n")) {
                                codeBuilder.append("\n");
                            }

                            // Append the "Copy Code" notice
                            String copyNotice = context.getString(R.string.text_copy_code_notice);

                            int start = codeBuilder.length();
                            codeBuilder.append(copyNotice);
                            int end = codeBuilder.length();

                            // Apply ClickableSpan to the copy notice
                            codeBuilder.setSpan(new ClickableSpan() {
                                @Override
                                public void onClick(@NonNull android.view.View widget) {
                                    // Copy the code to clipboard
                                    GlobalUtils.copyToClipboard(context, codeContent.trim());
                                    GlobalUtils.showToast(context, context.getString(R.string.toast_code_clipboard), false);
                                }

                                @Override
                                public void updateDrawState(@NonNull android.text.TextPaint ds) {
                                    // Customize the appearance of the notice
                                    ds.setColor(ds.linkColor);
                                    ds.setUnderlineText(false);
                                }
                            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                            // Align the copy notice to the bottom right
                            codeBuilder.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                            // Apply background color to the entire code block
                            codeBuilder.setSpan(new BackgroundColorSpan(Color.parseColor("#F5F5F5")),
                                    0, codeBuilder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                            // Append the modified code block to the visitor
                            visitor.builder().append(codeBuilder);
                        });
                    }
                })
                // LaTeX support
                .usePlugin(io.noties.markwon.ext.latex.JLatexMathPlugin.create(40, builder -> builder.inlinesEnabled(true)))
                // Image support
                .usePlugin(ImagesPlugin.create())
                // Inline parsing
                .usePlugin(MarkwonInlineParserPlugin.create())
                // Link recognition
                .usePlugin(LinkifyPlugin.create())
                // Custom plugin for preprocessing Markdown (if needed)
                .usePlugin(new AbstractMarkwonPlugin() {
                    @NonNull
                    @Override
                    public String processMarkdown(@NonNull String markdown) {
                        // Your existing preprocessing code here (if any)
                        return markdown;
                    }
                })
                // Build the Markwon instance
                .build();
    }

    public void render(TextView textView, String markdown) {
        if (textView != null && markdown != null) {
            try {
                markwon.setMarkdown(textView, markdown);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
