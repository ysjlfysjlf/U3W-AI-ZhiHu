package com.playwright.utils;

import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.playwright.websocket.WebSocketClientService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class ZhiHuUtil {
    @Autowired
    private LogMsgUtil logInfo;

    @Autowired
    private WebSocketClientService webSocketClientService;

    @Autowired
    private ClipboardLockManager clipboardLockManager;

    @Value("${cube.url}")
    private String url;

    @Autowired
    private ScreenshotUtil screenshotUtil;

    
    /**
     * 清理HTML内容，保留Markdown格式
     * 移除不必要的HTML元素和属性，保留有用的格式
     *
     * @param html 原始HTML内容
     * @return 清理后的HTML内容
     */
    private String cleanHtmlContent(String html) {
        try {
            Document doc = Jsoup.parse("<div>" + html + "</div>");
            
            // 移除所有财富号、新浪财经等标记
            doc.select("div.Popover, div.css-vurnku, div.css-1psy98, svg").remove();
            
            // 移除所有按钮元素
            doc.select("button, [tabindex]").remove();
            
            // 移除所有样式属性，但保留基本结构
            doc.select("[style]").removeAttr("style");
            
            // 移除所有ID属性
            doc.select("[id]").removeAttr("id");
            
            // 移除所有类名，但保留特定的类名
            Elements elementsWithClass = doc.select("[class]");
            for (Element element : elementsWithClass) {
                String className = element.attr("class");
                // 保留Render-markdown类，移除其他类
                if (!className.contains("Render-markdown")) {
                    element.removeAttr("class");
                }
            }
            
            // 移除所有data-*属性
            Elements allElements = doc.getAllElements();
            for (Element element : allElements) {
                element.attributes().forEach(attr -> {
                    if (attr.getKey().startsWith("data-")) {
                        element.removeAttr(attr.getKey());
                    }
                });
            }
            
            // 移除dir属性
            doc.select("[dir]").removeAttr("dir");
            
            // 移除空的div标签
            doc.select("div:empty").remove();
            
            // 获取处理后的内容
            return doc.body().html();
        } catch (Exception e) {
            System.out.println("清理HTML内容时出错: " + e.getMessage());
            return html; // 如果处理出错，返回原始内容
        }
    }
    

    /**
     * 等待知乎直答回复并获取内容
     *
     * @param page   Playwright页面实例
     * @param userId 用户ID
     * @return 知乎直答生成的内容
     */
    public String waitZHResponse(Page page, String userId, String aiName) throws InterruptedException {
        try {
            // 记录本轮和上一轮的HTML回答
            String currentContent = "";
            String lastContent = "";
    
            // 超时时间：10分钟
            long timeout = 600000;
            long startTime = System.currentTimeMillis();
            
            while (true) {
                // 如果超过超时，跳出
                if (System.currentTimeMillis() - startTime > timeout) {
                    System.out.println("超时，" + aiName + "未完成回答！");
                        break;
                        }
                    // 检测完成标志元素
                  boolean done = false;
                  Locator sendButton = page.locator("div.css-175oi2r svg[fill='#ffffff'][width='20'][height='20']");
                  done = sendButton.count() > 0;

                     // 获取整个Render-markdown内容
                    Locator responseArea = page.locator("div.Render-markdown").last();


                    // 自动滚动到最新内容，确保内容可见
                try {
                    // 使用纯 JavaScript 查找元素并滚动，不传递 Locator 对象
                    page.evaluate("() => { "
                            + "const elements = document.querySelectorAll('div.Render-markdown');"
                            + "if (elements.length > 0) {"
                            + "  elements[elements.length - 1].scrollIntoView({ behavior: 'auto', block: 'end' });"
                            + "}"
                            + "}");
                    Thread.sleep(500); // 等待滚动完成
                } catch (Exception e) {
                    logInfo.sendTaskLog("滚动到最新内容时出错: " + e.getMessage(), userId, aiName);
                }

                    // 获取完整的HTML内容
                    currentContent = responseArea.innerHTML();
        

                if(done && currentContent.equals(lastContent)){
                    logInfo.sendTaskLog(aiName + "回答完成，正在提取内容", userId, aiName);
                    break;
                }

                lastContent = currentContent;

                Thread.sleep(5000);
            }
            
            logInfo.sendTaskLog(aiName + "知乎直答内容已提取完成", userId, aiName);
            
            // 清理HTML内容
            currentContent = cleanHtmlContent(currentContent);
            
            //返回处理后的内容（保留Markdown格式）
            return currentContent;
        } catch (Exception e) {
            e.printStackTrace();
             return "获取内容失败";
        }
    }


    /**
     * 处理知乎直答的分享流程
     * 1. 点击分享按钮
     * 2. 点击复制链接按钮，获取分享链接
     * 3. 点击保存图片按钮
     * 4. 点击复制图片按钮，获取图片
     * 5. 获取分享后的纯文本内容
     *
     * @param page Playwright页面实例
     * @param userId 用户ID
     * @param uploadUrl 上传URL
     * @return 包含分享链接、图片URL和分享后内容的数组 [shareUrl, shareImgUrl]
     */
    public String[] handleZhihuShare(Page page, String userId, String uploadUrl) throws IOException, InterruptedException {
        String shareImgUrl;

        AtomicReference<String> shareUrlRef = new AtomicReference<>();

        clipboardLockManager.runWithClipboardLock(()->{
            try {
                // 点击分享按钮
                page.locator("//*[@id=\"fullScreen\"]/div[1]/div/div[1]/div[2]/div[3]").click();
                Thread.sleep(1000);

                //点击复制链接按钮
                page.locator("//*[@id=\"fullScreen\"]/div[1]/div/div[1]/div/div[2]/div[3]").click();
                Thread.sleep(1000);
                // 从剪贴板获取链接
                String shareUrl = (String) page.evaluate("navigator.clipboard.readText()");
                shareUrlRef.set(shareUrl);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        Thread.sleep(1000);
        String shareUrl=shareUrlRef.get();

        //点击保存图片按钮
        page.locator("//*[@id=\"fullScreen\"]/div[1]/div/div[1]/div/div[2]/div[2]").click();
        // 等待
        Thread.sleep(3000);

        //点击下载图片按钮
        shareImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
            page.getByText("下载图片").click();
        });

        logInfo.sendTaskLog("知乎直答内容已自动提取完成", userId, "知乎直答");

        return new String[]{shareUrl, shareImgUrl};
    }
}
