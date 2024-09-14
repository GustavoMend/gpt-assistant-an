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
                    public String processMarkdown(@NonNull String markdown) { // 预处理MD文本
                        List<String> sepList = new ArrayList<>(Arrays.asList(markdown.split("```", -1)));
                        for (int i = 0; i < sepList.size(); i += 2) { // 跳过代码块不处理
                            // 解决仅能渲染“$$...$$”公式的问题
                            String regexDollar = "(?<!\\$)\\$(?!\\$)([^\\n]*?)(?<!\\$)\\$(?!\\$)"; // 匹配单行内的“$...$”
                            String regexBrackets = "(?s)\\\\\\[(.*?)\\\\\\]"; // 跨行匹配“\[...\]”
                            String regexParentheses = "\\\\\\(([^\\n]*?)\\\\\\)"; // 匹配单行内的“\(...\)”
                            String latexReplacement = "\\$\\$$1\\$\\$"; // 替换为“$$...$$”
                            // 为图片添加指向同一URL的链接
                            String regexImage = "!\\[(.*?)\\]\\((.*?)\\)"; // 匹配“![...](...)”
                            String imageReplacement = "[$0]($2)"; // 替换为“[![...](...)](...)”
                            // 进行替换
                            sepList.set(i, sepList.get(i).replaceAll(regexDollar, latexReplacement)
                                    .replaceAll(regexBrackets, latexReplacement)
                                    .replaceAll(regexParentheses, latexReplacement)
                                    .replaceAll(regexImage, imageReplacement));
                        }
                        return String.join("```", sepList);
                    }
                })
                .usePlugin(new AbstractMarkwonPlugin() { // 设置图片大小
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
//                .usePlugin(TablePlugin.create(context)) // unstable
//                .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
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
