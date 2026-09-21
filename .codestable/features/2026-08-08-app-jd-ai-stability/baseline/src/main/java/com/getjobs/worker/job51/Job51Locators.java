package com.getjobs.worker.job51;

/**
 * APP 岗位详情与沟通区域的定位候选。
 * 页面结构变化时只需在这里调整候选顺序。
 */
public final class Job51Locators {
    private Job51Locators() {
    }

    public static final String[] DESCRIPTION_SELECTORS = {
            ".job-detail",
            ".job-detail-content",
            ".job-detail-main",
            "[class*='job-intro']",
            "[class*='job-description']",
            "[class*='job-detail']",
            "[class*='job-duty']",
            ".job-intro-container",
            ".job-intro"
    };

    public static final String[] JOB_CARD_SELECTORS = {
            ".joblist-item-job-wrapper > .joblist-item",
            ".joblist-item-job-wrapper .joblist-item",
            ".joblist-item"
    };

    public static final String[] GREETING_TEXTS = {
            "打招呼",
            "在线沟通",
            "立即沟通",
            "联系HR",
            "联系 HR",
            "发消息",
            "沟通"
    };

    public static final String[] MESSAGE_INPUT_SELECTORS = {
            "textarea[placeholder*='消息']:visible",
            "textarea[placeholder*='沟通']:visible",
            "textarea[placeholder*='文字']:visible",
            "textarea:visible",
            "[contenteditable='true']:visible",
            "input[type='text']:visible"
    };

    public static final String[] SEND_TEXTS = {"发送", "发消息", "提交"};

    public static final String[] SUCCESS_TEXTS = {"发送成功", "消息已发送"};

    public static final String[] OUTGOING_MESSAGE_SELECTORS = {
            ".message-self:visible",
            ".message-send:visible",
            ".im-ui-txt.send:visible",
            "[class*='message'][class*='right']:visible",
            "[class*='msg'][class*='self']:visible"
    };
}
