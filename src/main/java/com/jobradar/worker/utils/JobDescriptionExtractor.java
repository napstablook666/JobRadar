package com.jobradar.worker.utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts job-related text from a recruitment detail page.
 *
 * <p>Callers should prefer platform API fields and precise selectors. This class is
 * the last-resort page-text path and removes common navigation, dialog, and
 * recommendation noise before the text is sent to an AI service.</p>
 */
public final class JobDescriptionExtractor {
    public static final int MAX_TEXT_LENGTH = 12_000;

    private static final int MIN_TEXT_LENGTH = 20;
    private static final Pattern SPACE_PATTERN = Pattern.compile("[\\t\\f ]+");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]{1,200}>");
    private static final Pattern ZERO_WIDTH_PATTERN = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final Pattern NOISE_LINE_PATTERN = Pattern.compile(
            "^(?:登录|注册|扫码登录|微信登录|手机登录|返回首页|返回顶部|收藏职位|分享职位|举报职位|"
                    + "下载客户端|打开客户端|推荐职位|相关职位|相似职位|猜你喜欢|职位推荐|公司推荐|"
                    + "查看更多职位|加载更多职位|隐私政策|用户协议|帮助中心|联系客服|意见反馈)$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOISE_LINES = Set.of(
            "首页", "登录", "注册", "扫码登录", "微信登录", "手机登录", "返回", "返回顶部",
            "收藏", "分享", "举报", "关闭", "取消", "确定", "搜索", "筛选", "清空",
            "上一页", "下一页", "加载更多", "查看更多", "打开app", "下载app",
            "推荐职位", "相关职位", "相似职位", "猜你喜欢", "热门职位", "职位推荐",
            "公司推荐", "隐私政策", "用户协议", "帮助中心", "联系客服", "意见反馈",
            "立即投递", "立即申请", "立即沟通", "在线沟通", "发消息", "打招呼"
    );

    private static final String[] CONTENT_MARKERS = {
            "职位描述", "岗位描述", "岗位职责", "工作职责", "工作内容", "职位介绍",
            "岗位介绍", "任职要求", "任职资格", "职位要求", "岗位要求", "招聘要求",
            "专业要求", "技能要求"
    };

    private static final String[] TRAILING_MARKERS = {
            "公司信息", "公司简介", "企业信息", "联系我们", "相关推荐", "相关职位",
            "相似职位", "猜你喜欢", "职位推荐", "推荐职位", "举报职位", "版权声明"
    };

    /**
     * Clone the page body in the browser, remove common UI nodes, and read its
     * rendered text from an off-screen container. The live page is not modified.
     */
    private static final String CLEAN_PAGE_SCRIPT = """
            () => {
              if (!document.body) return "";
              const root = document.body.cloneNode(true);
              const removeSelectors = [
                "script", "style", "noscript", "template", "svg", "canvas",
                "iframe", "header", "nav", "footer", "aside", "form",
                "button", "input", "textarea", "select",
                "[role='dialog']", "[role='alert']", "[aria-modal='true']",
                "[aria-hidden='true']"
              ];
              root.querySelectorAll(removeSelectors.join(",")).forEach(el => el.remove());

              const noisy = /(?:^|[-_ ])(?:nav|header|footer|sidebar|breadcrumb|pagination|pager|recommend|related|similar|guess|advert|banner|popup|modal|dialog|toast|login|register|download|share|report|feedback|copyright|客服)(?:$|[-_ ])/i;
              const content = /(?:job|post|position|detail|description|intro|duty|require|responsib|company|salary|work|招聘|岗位|职位|任职|职责|要求)/i;
              root.querySelectorAll("*").forEach(el => {
                const className = typeof el.className === "string" ? el.className : "";
                const meta = [el.id || "", className, el.getAttribute("aria-label") || "", el.getAttribute("data-testid") || ""].join(" ");
                if (noisy.test(meta) && !content.test(meta)) el.remove();
              });

              const holder = document.createElement("div");
              holder.style.cssText = "position:fixed;left:-100000px;top:0;width:1200px;opacity:0;pointer-events:none";
              holder.append(root);
              document.documentElement.append(holder);
              try {
                return root.innerText || root.textContent || "";
              } finally {
                holder.remove();
              }
            }
            """;

    private JobDescriptionExtractor() {
    }

    /** Try precise selectors first, then use cleaned visible page text as a fallback. */
    public static String extractFromPage(Page page, String... preferredSelectors) {
        if (page == null) return null;
        String precise = extractFromSelectors(page, preferredSelectors);
        if (isUsable(precise)) return precise;

        try {
            Object value = page.evaluate(CLEAN_PAGE_SCRIPT);
            return extractRelevantText(value == null ? null : String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Extract only from supplied selectors without falling back to the entire page. */
    public static String extractFromSelectors(Page page, String... selectors) {
        if (page == null || selectors == null) return null;
        for (String selector : selectors) {
            if (selector == null || selector.isBlank()) continue;
            try {
                Locator candidates = page.locator(selector);
                int count = Math.min(candidates.count(), 8);
                for (int i = 0; i < count; i++) {
                    Locator candidate = candidates.nth(i);
                    if (!candidate.isVisible()) continue;
                    String text = extractRelevantText(candidate.innerText());
                    if (isUsable(text)) return text;
                }
            } catch (Exception ignored) {
                // Continue with the next selector.
            }
        }
        return null;
    }

    /** Clean, keep the relevant job section, and bound text before caching or AI use. */
    public static String normalize(String raw) {
        return extractRelevantText(raw);
    }

    /** Clean HTML fragments, whitespace, duplicate short UI lines, and common UI labels. */
    public static String cleanText(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.replace('\u00A0', ' ')
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        value = HTML_TAG_PATTERN.matcher(value).replaceAll(" ");
        value = ZERO_WIDTH_PATTERN.matcher(value).replaceAll("");

        List<String> lines = new ArrayList<>();
        Set<String> repeated = new HashSet<>();
        for (String rawLine : value.split("\\n+")) {
            String line = SPACE_PATTERN.matcher(rawLine).replaceAll(" ").trim();
            if (line.isBlank()) continue;
            String compact = line.replaceAll("\\s+", "");
            // Keep section boundaries until extractRelevantText can cut the recommendation tail.
            if (isNoiseLine(compact) && !containsMarker(compact, TRAILING_MARKERS)) continue;
            if (repeated.contains(line) && line.length() < 80) continue;
            repeated.add(line);
            lines.add(line);
        }
        if (lines.isEmpty()) return null;
        return String.join("\n", lines).trim();
    }

    /**
     * Keep the job section when a recognizable heading exists. Otherwise retain
     * cleaned page text up to the first recommendation/company boundary.
     */
    public static String extractRelevantText(String raw) {
        String cleaned = cleanText(raw);
        if (!isUsable(cleaned)) return null;

        int start = firstMarkerIndex(cleaned, CONTENT_MARKERS, 0);
        int trailing = firstMarkerIndex(cleaned, TRAILING_MARKERS, 0);
        if (trailing >= 0 && trailing < start) return null;
        if (start < 0) {
            return limit(trailing >= 0 ? cleaned.substring(0, trailing).trim() : cleaned);
        }

        int end = firstMarkerIndex(cleaned, TRAILING_MARKERS, start + 1);
        String section = (end > start ? cleaned.substring(start, end) : cleaned.substring(start)).trim();
        return section.length() < MIN_TEXT_LENGTH ? null : limit(section);
    }

    public static boolean isUsable(String text) {
        if (text == null || text.isBlank() || text.length() < MIN_TEXT_LENGTH) return false;
        String compact = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return !compact.contains("访问验证")
                && !compact.contains("安全验证")
                && !compact.contains("人机验证")
                && !compact.contains("请完成验证")
                && !compact.contains("验证码")
                && !compact.contains("滑块验证")
                && !compact.contains("请按住滑块")
                && !compact.equals("登录")
                && !compact.equals("请登录");
    }

    private static boolean isNoiseLine(String compact) {
        if (compact.isBlank()) return true;
        String lower = compact.toLowerCase(Locale.ROOT);
        return NOISE_LINES.contains(lower) || NOISE_LINE_PATTERN.matcher(compact).matches();
    }

    private static boolean containsMarker(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    private static int firstMarkerIndex(String text, String[] markers, int fromIndex) {
        int result = -1;
        for (String marker : markers) {
            int index = text.indexOf(marker, fromIndex);
            if (index >= 0 && (result < 0 || index < result)) result = index;
        }
        return result;
    }

    private static String limit(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) return value;
        return value.substring(0, MAX_TEXT_LENGTH).trim();
    }
}
