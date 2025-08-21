package com.playwright.controller;

import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.playwright.entity.UserInfoRequest;
import com.playwright.utils.*;
import com.playwright.websocket.WebSocketClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
/**
 * AI生成内容控制器
 * 处理与各大AI平台（腾讯元宝、豆包等）的交互操作
 * @author 优立方
 * @version JDK 17
 * @date 2025年01月21日 08:53
 */
@RestController
@RequestMapping("/api/browser")
@Tag(name = "AI生成内容控制器", description = "处理与各大AI平台（腾讯元宝、豆包等）的交互操作")
public class AIGCController {

    // 依赖注入 注入webSocketClientService 进行消息发送
    private final WebSocketClientService webSocketClientService;

    // 构造器注入WebSocket服务
    public AIGCController(WebSocketClientService webSocketClientService) {
        this.webSocketClientService = webSocketClientService;
    }

    @Autowired
    private ZhiHuUtil zhiHuUtil;

    @Autowired
    private DeepSeekUtil deepSeekUtil;

    // 从配置文件中注入URL 调用远程API存储数据
    @Value("${cube.url}")
    private String url;

    // 腾讯元宝相关操作工具类
    @Autowired
    private TencentUtil tencentUtil;

    // 豆包相关操作工具类
    @Autowired
    private DouBaoUtil douBaoUtil;

    // MiniMax相关操作工具类
    @Autowired
    private MiniMaxUtil miniMaxUtil;

    @Autowired
    private QwenUtil qwenUtil;

    // 日志记录工具类
    @Autowired
    private LogMsgUtil logInfo;

    // 浏览器操作工具类
    @Autowired
    private BrowserUtil browserUtil;

    // 浏览器截图操作工具类
    @Autowired
    private ScreenshotUtil screenshotUtil;

    @Autowired
    private ClipboardLockManager clipboardLockManager;

    @Value("${cube.uploadurl}")
    private String uploadUrl;

    /**
     * 创建安全的截图任务
     * @param page 页面对象
     * @param userId 用户ID
     * @param aiName AI名称
     * @return 截图任务管理对象，包含停止方法
     */
    private ScreenshotTaskManager createSafeScreenshotTask(Page page, String userId, String aiName) {
        AtomicInteger i = new AtomicInteger(0);
        ScheduledExecutorService screenshotExecutor = Executors.newSingleThreadScheduledExecutor();
        AtomicBoolean shouldStopScreenshot = new AtomicBoolean(false);

        ScheduledFuture<?> screenshotFuture = screenshotExecutor.scheduleAtFixedRate(() -> {
            try {
                // 检查是否应该停止截图
                if (shouldStopScreenshot.get()) {
                    return;
                }
                
                // 检查页面是否已关闭，如果页面关闭则退出
                if (page == null || page.isClosed()) {
                    System.out.println("页面已关闭，停止" + aiName + "截图任务");
                    shouldStopScreenshot.set(true);
                    return;
                }
                
                // 检查浏览器上下文状态
                try {
                    page.context().browser();
                } catch (Exception e) {
                    System.out.println("浏览器上下文已关闭，停止" + aiName + "截图任务");
                    shouldStopScreenshot.set(true);
                    return;
                }
                
                int currentCount = i.getAndIncrement();
                logInfo.sendImgData(page, userId + aiName + "执行过程截图" + currentCount, userId);
            } catch (com.microsoft.playwright.PlaywrightException e) {
                // 处理Playwright特定异常
                if (e.getMessage().contains("Object doesn't exist") || 
                    e.getMessage().contains("worker@") ||
                    e.getMessage().contains("Target closed") ||
                    e.getMessage().contains("Page closed")) {
                    System.out.println("页面或浏览器已关闭，停止" + aiName + "截图任务: " + e.getMessage());
                    shouldStopScreenshot.set(true);
                    return;
                }
                System.out.println(aiName + "截图任务Playwright异常: " + e.getMessage());
            } catch (Exception e) {
                System.out.println(aiName + "截图任务异常: " + e.getMessage());
                e.printStackTrace();
            }
        }, 0, 8, TimeUnit.SECONDS);

        return new ScreenshotTaskManager(screenshotFuture, screenshotExecutor, shouldStopScreenshot);
    }

    /**
     * 截图任务管理类
     */
    private static class ScreenshotTaskManager {
        private final ScheduledFuture<?> screenshotFuture;
        private final ScheduledExecutorService screenshotExecutor;
        private final AtomicBoolean shouldStopScreenshot;

        public ScreenshotTaskManager(ScheduledFuture<?> screenshotFuture, 
                                   ScheduledExecutorService screenshotExecutor, 
                                   AtomicBoolean shouldStopScreenshot) {
            this.screenshotFuture = screenshotFuture;
            this.screenshotExecutor = screenshotExecutor;
            this.shouldStopScreenshot = shouldStopScreenshot;
        }

        public void stop() {
            shouldStopScreenshot.set(true);
            screenshotFuture.cancel(false);
            screenshotExecutor.shutdown();
            
            try {
                if (!screenshotExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    screenshotExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                screenshotExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 处理多AI代理的请求
     * @param userInfoRequest 包含用户ID、角色、提示信息等
     * @return 拼接的生成内容
     */
    @Operation(summary = "处理多AI代理请求", description = "根据用户输入启动多个AI平台代理任务")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startAgent")
    public String startAgent(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest) {
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false,userInfoRequest.getUserId(),"agent")) {

              // 初始化变量
              String userId = userInfoRequest.getUserId();
              String roles = userInfoRequest.getRoles();
              String userPrompt = userInfoRequest.getUserPrompt();
              String isNewChat = userInfoRequest.getIsNewChat();
              String copiedText = "";
              int wrightCopyCount = 0;


              // 根据不同的AI角色创建对应的页面实例
              Page[] pages = new Page[5];

              // 处理 智能体 代理
              if(roles.contains("cube-trubos-agent")){
                  logInfo.sendTaskLog( "AI搜索@元器准备就绪，正在打开页面",userId,"AI搜索@元器");
                  pages[0] = context.newPage();
                  String agentUrl = "https://yuanbao.tencent.com/chat/58LgTturCBdj/";
                  wrightCopyCount = tencentUtil.handelAgentAI(pages[0],userPrompt,agentUrl,"AI搜索@元器",userId,isNewChat);
              }
              if(roles.contains("cube-turbos-large-agent")){
                  logInfo.sendTaskLog( "数智化助手@元器准备就绪，正在打开页面",userId,"数智化助手@元器");
                  pages[1] = context.newPage();
                  String agentUrl = "https://yuanbao.tencent.com/chat/rgzZDhQdsMHZ/";
                  wrightCopyCount = tencentUtil.handelAgentAI(pages[1],userPrompt,agentUrl,"数智化助手@元器",userId,isNewChat);
              }
              if(roles.contains("cube-mini-max-agent")){
                  logInfo.sendTaskLog( "MiniMax@元器准备就绪，正在打开页面",userId,"MiniMax@元器");
                  pages[2] = context.newPage();
                  String agentUrl = "https://yuanbao.tencent.com/chat/7kNJBgAgQFet/";
                  wrightCopyCount = tencentUtil.handelAgentAI(pages[2],userPrompt,agentUrl,"MiniMax@元器",userId,isNewChat);
              }
              if(roles.contains("mini-max-agent")){
                  logInfo.sendTaskLog( "MiniMax Chat准备就绪，正在打开页面",userId,"MiniMax Chat");
                  pages[2] = context.newPage();
                  String agentUrl = "https://chat.minimaxi.com/";
                  wrightCopyCount = tencentUtil.handelAgentAI(pages[2],userPrompt,agentUrl,"MiniMax Chat",userId,isNewChat);
              }
              if(roles.contains("cube-sogou-agent")){
                  logInfo.sendTaskLog( "搜狗搜索@元器准备就绪，正在打开页面",userId,"搜狗搜索@元器");
                  pages[3] = context.newPage();
                  String agentUrl = "https://yuanbao.tencent.com/chat/u1VeB6jKt0lE/";
                  wrightCopyCount = tencentUtil.handelAgentAI(pages[3],userPrompt,agentUrl,"搜狗搜索@元器",userId,isNewChat);
              }
              if(roles.contains("cube-lwss-agent")){
                  logInfo.sendTaskLog( "KIMI@元器准备就绪，正在打开页面",userId,"KIMI@元器");
                  pages[4] = context.newPage();
                  String agentUrl = "https://yuanbao.tencent.com/chat/oq4esMyN9VS2/";
                  wrightCopyCount = tencentUtil.handelAgentAI(pages[4],userPrompt,agentUrl,"KIMI@元器",userId,isNewChat);
              }

             // 保存各代理生成的数据并拼接结果
              if(roles.contains("cube-trubos-agent")){
                  copiedText = copiedText +"\n\n"+ tencentUtil.saveAgentDraftData(pages[0],userInfoRequest,"cube-trubos-agent",userId,wrightCopyCount,"AI搜索@元器","RETURN_TURBOS_RES");
              }
              if(roles.contains("cube-turbos-large-agent")){
                  copiedText = copiedText +"\n\n"+ tencentUtil.saveAgentDraftData(pages[1],userInfoRequest,"cube-turbos-large-agent",userId,wrightCopyCount,"数智化助手@元器","RETURN_TURBOS_LARGE_RES");
              }
              if(roles.contains("cube-mini-max-agent")){
                  copiedText = copiedText +"\n\n"+ tencentUtil.saveAgentDraftData(pages[2],userInfoRequest,"cube-mini-max-agent",userId,wrightCopyCount,"MiniMax@元器","RETURN_MINI_MAX_RES");
              }
              if(roles.contains("mini-max-agent")){
                  copiedText = copiedText +"\n\n"+ tencentUtil.saveAgentDraftData(pages[2],userInfoRequest,"mini-max-agent",userId,wrightCopyCount,"MiniMax Chat","RETURN_MINI_MAX_RES");
              }
              if(roles.contains("cube-sogou-agent")){
                  copiedText = copiedText +"\n\n"+ tencentUtil.saveAgentDraftData(pages[3],userInfoRequest,"cube-sogou-agent",userId,wrightCopyCount,"搜狗搜索@元器","RETURN_SOGOU_RES");
              }
              if(roles.contains("cube-lwss-agent")){
                  copiedText = copiedText +"\n\n"+ tencentUtil.saveAgentDraftData(pages[4],userInfoRequest,"cube-lwss-agent",userId,wrightCopyCount,"KIMI@元器","RETURN_LWSS_RES");
              }
               return copiedText;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "获取内容失败";
    }

    /**
     * 处理腾讯元宝平台的请求
     * @param userInfoRequest 包含用户信息和会话参数
     * @return 生成的内容（当前版本暂未实现内容返回）
     */
    @Operation(summary = "启动腾讯元宝内容生成", description = "根据角色执行不同类型的腾讯元宝任务（T1和DS）")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startYB")
    public String startYB(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest) {
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false,userInfoRequest.getUserId(),"yb")) {

               String userId =userInfoRequest.getUserId();
               String currentContent = "";
               String roles = userInfoRequest.getRoles();
               String userPrompt = userInfoRequest.getUserPrompt();
               String t1ChatId = userInfoRequest.getToneChatId();
               String dschatId = userInfoRequest.getYbDsChatId();

               Page[] pages = new Page[2];
               int t1CopyCount = 0;
               int dsCopyCount = 0;

               //腾讯元宝T1  根据角色组合处理不同模式（普通/深度思考/联网）
               logInfo.sendTaskLog( "腾讯元宝准备就绪，正在打开页面",userId,"腾讯元宝T1");
               if(roles.contains("yb-hunyuan-pt")&& !roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")){
                   pages[0] = context.newPage();
                   t1CopyCount =  tencentUtil.handleYBAI(pages[0], userPrompt, "yb-hunyuan-pt",userId,"腾讯元宝T1",t1ChatId);
               }else if(roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")){
                   //深度思考
                   pages[0] = context.newPage();
                   t1CopyCount = tencentUtil.handleYBAI( pages[0], userPrompt, "yb-hunyuan-sdsk",userId,"腾讯元宝T1",t1ChatId);
               }else if(roles.contains("yb-hunyuan-lwss") && !roles.contains("yb-hunyuan-sdsk")){
                   //深度思考 + 联网
                   pages[0] = context.newPage();
                   t1CopyCount = tencentUtil.handleYBAI( pages[0], userPrompt, "yb-hunyuan-lwss-1",userId,"腾讯元宝T1",t1ChatId);
               }else if(roles.contains("yb-hunyuan-lwss") && roles.contains("yb-hunyuan-sdsk")){
                   //深度思考 + 联网
                   pages[0] = context.newPage();
                   t1CopyCount = tencentUtil.handleYBAI( pages[0], userPrompt, "yb-hunyuan-lwss-2",userId,"腾讯元宝T1",t1ChatId);
               }

            //腾讯元宝DS  根据角色组合处理不同模式（普通/深度思考/联网）
               if(roles.contains("yb-deepseek-pt") && !roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")){
                   pages[1] = context.newPage();
                   dsCopyCount = tencentUtil.handleYBAI(pages[1],userPrompt,"yb-deepseek-pt",userId,"腾讯元宝DS",dschatId);
               }else  if(roles.contains("yb-deepseek-sdsk")  && !roles.contains("yb-deepseek-lwss")){
                   //深度思考
                   pages[1] = context.newPage();
                   dsCopyCount = tencentUtil.handleYBAI(pages[1],userPrompt,"yb-deepseek-sdsk",userId,"腾讯元宝DS",dschatId);
               }else if(roles.contains("yb-deepseek-lwss") && !roles.contains("yb-deepseek-sdsk") ){
                   //深度思考 + 联网
                   pages[1] = context.newPage();
                   dsCopyCount = tencentUtil.handleYBAI(pages[1],userPrompt,"yb-deepseek-lwss-1",userId,"腾讯元宝DS",dschatId);
               }else if(roles.contains("yb-deepseek-lwss") && roles.contains("yb-deepseek-sdsk") ){
                   //深度思考 + 联网
                   pages[1] = context.newPage();
                   dsCopyCount = tencentUtil.handleYBAI(pages[1],userPrompt,"yb-deepseek-lwss-2",userId,"腾讯元宝DS",dschatId);
               }


               //保存入库 腾讯元宝T1
               if(roles.contains("yb-hunyuan-pt")&& !roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")){
                   currentContent = currentContent +"\n\n"+ tencentUtil.saveDraftData(pages[0],userInfoRequest,"yb-hunyuan-pt",userId,t1CopyCount);
               }else if(roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")){
                   //深度思考
                   currentContent = currentContent +"\n\n"+ tencentUtil.saveDraftData(pages[0],userInfoRequest,"yb-hunyuan-sdsk",userId,t1CopyCount);
               }else if(roles.contains("yb-hunyuan-lwss")){
                   //深度思考 + 联网
                   currentContent = currentContent +"\n\n"+ tencentUtil.saveDraftData(pages[0],userInfoRequest,"yb-hunyuan-lwss",userId,t1CopyCount);
               }

                //保存入库 腾讯元宝DS
               if(roles.contains("yb-deepseek-pt") && !roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")){
                   currentContent = currentContent +"\n\n"+ tencentUtil.saveDraftData(pages[1],userInfoRequest,"yb-deepseek-pt",userId,dsCopyCount);
               }else  if(roles.contains("yb-deepseek-sdsk")  && !roles.contains("yb-deepseek-lwss")){
                   currentContent = currentContent +"\n\n"+ tencentUtil.saveDraftData(pages[1],userInfoRequest,"yb-deepseek-sdsk",userId,dsCopyCount);
               }else if(roles.contains("yb-deepseek-lwss")){
                   //深度思考 + 联网
                   currentContent = currentContent +"\n\n"+ tencentUtil.saveDraftData(pages[1],userInfoRequest,"yb-deepseek-lwss",userId,dsCopyCount);
               }
               return currentContent;

        } catch (Exception e) {
            e.printStackTrace();
        }
        return "获取内容失败";
    }

    /**
     * 处理MiniMax的常规请求
     * @param userInfoRequest 包含会话ID和用户指令
     * @return AI生成的文本内容
     */
    @Operation(summary = "启动MiniMaxAI生成", description = "调用MiniMax平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startMiniMax")
    public String startMiniMax(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest){
        try (
                BrowserContext context = browserUtil.createPersistentBrowserContext(false,userInfoRequest.getUserId(),"MiniMax Chat")) {

            // 初始化变量
            String userId = userInfoRequest.getUserId();
            String maxChatId = userInfoRequest.getMaxChatId();
            logInfo.sendTaskLog( "MiniMax准备就绪，正在打开页面",userId,"MiniMax Chat");
            // 获取是否开启深度思考和联网搜索
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();

            // 初始化页面并导航到指定会话
            Page page = context.newPage();
            if(maxChatId != null && !maxChatId.isEmpty()){
                page.navigate("https://chat.minimaxi.com/?type=chat&chatID="+maxChatId);
            }else {
                page.navigate("https://chat.minimaxi.com/");
            }
            page.waitForLoadState(LoadState.LOAD);
            // 去除弹窗
            Locator elements = page.locator(".md\\:hover\\:bg-col_text05.text-col_text02.md\\:text-col_text03.z-50.flex.h-\\[30px\\].w-\\[30px\\].cursor-pointer.items-center.justify-center.rounded");

            if (elements.count() > 0) {
                elements.first().click();
            }


            page.waitForTimeout(2000);
            Locator aside = page.locator("aside.shadow-s1");
            System.out.println("元素"+aside.evaluate("e => e.outerHTML"));
            if (aside.count() > 0) {
                aside.first().evaluate("el => el.remove()");
            }


            logInfo.sendTaskLog( "MiniMax页面打开完成",userId,"MiniMax Chat");
            // 定位深度思考按钮
            String deepThinkPath= maxChatId != null && !maxChatId.isEmpty()?
                    "xpath=/html/body/section/div/div/section/div/div[1]/div/div/main/div[3]/div[2]/div/div[2]/div[1]/div[2]":
                    "xpath=/html/body/section/div/div/section/div/div[1]/div/div/div/div/div/div[3]/div[2]/div/div[2]/div[1]/div[2]";
            Locator deepThoughtButton = page.locator(deepThinkPath);
            // 判断深度思考按钮是否已点击（默认打开时，深度思考和联网都已经打开）
            boolean isActiveForSK = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                    "    return element.classList.contains('bg-col_brand00/[0.06]') && \n" +
                    "element.classList.contains('text-col_brand00') && "+
                    "element.classList.contains('border-col_brand00/[0.16]');"+
                    "}");

            // 定位联网搜索按钮
            String internetPath= maxChatId != null && !maxChatId.isEmpty()?
                    "xpath=/html/body/section/div/div/section/div/div[1]/div/div/main/div[3]/div[2]/div/div[2]/div[1]/div[1]":
                    "xpath=/html/body/section/div/div/section/div/div[1]/div/div/div/div/div/div[3]/div[2]/div/div[2]/div[1]/div[1]";
            Locator internetThoughtButton = page.locator(internetPath);
            boolean isActiveForLW = (Boolean) internetThoughtButton.evaluate("element => {\n" +
                    "    return element.classList.contains('bg-col_brand00/[0.06]') &&  \n" +
                    "element.classList.contains('text-col_brand00') &&  "+
                    "element.classList.contains('border-col_brand00/[0.16]');"+
                    "}");
            // 开启深度思考
            if (isActiveForSK  && roles.contains("max-sdsk")) {
                Thread.sleep(1000);
                // 再次检查按钮状态
                isActiveForSK = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                        "    return element.classList.contains('bg-col_brand00/[0.06]') && \n" +
                        "element.classList.contains('text-col_brand00') && "+
                        "element.classList.contains('border-col_brand00/[0.16]');"+
                        "}");
                // 如果未开启则点击
                if (!isActiveForSK) {
                    deepThoughtButton.click();
                    Thread.sleep(1000);
                }
                logInfo.sendTaskLog( "已启动深度思考模式",userId,"MiniMax Chat");
            }else{
                // 未开启深度思考模式
                // 未开启需要点击深度思考按钮来关闭深度思考
                deepThoughtButton.click();
            }
            // 开启联网
            if (isActiveForLW  && roles.contains("max-lwss")) {
                Thread.sleep(1000);
                // 再次检查按钮状态
                isActiveForLW = (Boolean) internetThoughtButton.evaluate("element => {\n" +
                        "    return element.classList.contains('bg-col_brand00/[0.06]') && \n" +
                        "element.classList.contains('text-col_brand00') && "+
                        "element.classList.contains('border-col_brand00/[0.16]');"+
                        "}");
                // 如果未开启则点击
                if (!isActiveForLW) {
                    internetThoughtButton.click();
                    Thread.sleep(1000);
                }
                logInfo.sendTaskLog( "已启动联网模式",userId,"MiniMax Chat");
            }else{
                // 未开启联网搜索模式
                // 未开启需要点击搜索按钮关闭搜索
                internetThoughtButton.click();
            }

            Locator aside1 = page.locator("aside.shadow-s1");
            if (aside1.count() > 0) {
                aside1.first().evaluate("el => el.remove()");
                System.out.println("已清除aside浮窗");
            }


            // 获取输入框并输入内容
            Thread.sleep(1000);
            page.locator("//*[@id=\"chat-input\"]").nth(1).click();
            Thread.sleep(1000);
            page.locator("//*[@id=\"chat-input\"]").nth(1).fill(userPrompt);
            logInfo.sendTaskLog( "用户指令已自动输入完成",userId,"MiniMax Chat");
            Thread.sleep(1000);
            page.locator("//*[@id=\"chat-input\"]").nth(1).press("Enter");
            logInfo.sendTaskLog("指令已自动发送成功", userId, "MiniMax Chat");


            // 创建定时截图线程
            AtomicInteger i = new AtomicInteger(0);
            ScheduledExecutorService screenshotExecutor = Executors.newSingleThreadScheduledExecutor();
            AtomicBoolean shouldStopScreenshot = new AtomicBoolean(false);
            
            // 启动定时任务，每5秒执行一次截图
            ScheduledFuture<?> screenshotFuture = screenshotExecutor.scheduleAtFixedRate(() -> {
                try {
                    // 检查是否应该停止截图
                    if (shouldStopScreenshot.get()) {
                        return;
                    }
                    
                    // 检查页面是否已关闭，如果页面关闭则退出
                    if (page == null || page.isClosed()) {
                        System.out.println("页面已关闭，停止MiniMax截图任务");
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    
                    // 检查浏览器上下文状态
                    try {
                        page.context().browser();
                    } catch (Exception e) {
                        System.out.println("浏览器上下文已关闭，停止MiniMax截图任务");
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    
                    int currentCount = i.getAndIncrement(); // 获取当前值并自增
                    logInfo.sendImgData(page, userId + "MiniMax执行过程截图"+currentCount, userId);

                } catch (com.microsoft.playwright.PlaywrightException e) {
                    // 处理Playwright特定异常
                    if (e.getMessage().contains("Object doesn't exist") || 
                        e.getMessage().contains("worker@") ||
                        e.getMessage().contains("Target closed") ||
                        e.getMessage().contains("Page closed")) {
                        System.out.println("页面或浏览器已关闭，停止MiniMax截图任务: " + e.getMessage());
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    System.out.println("MiniMax截图任务Playwright异常: " + e.getMessage());
                } catch (Exception e) {
                    System.out.println("MiniMax截图任务异常: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 0, 8, TimeUnit.SECONDS);

            logInfo.sendTaskLog( "开启自动监听任务，持续监听MiniMax回答中",userId,"MiniMax Chat");
            //等待html片段获取完成
            String copiedText =  miniMaxUtil.waitMiniMaxHtmlDom(page,userId,"MiniMax Chat");
            
            // 停止截图任务
            shouldStopScreenshot.set(true);
            screenshotFuture.cancel(false);
            screenshotExecutor.shutdown();
            
            // 等待截图任务完全停止
            try {
                if (!screenshotExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    screenshotExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                screenshotExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            AtomicReference<String> shareUrlRef = new AtomicReference<>();

            clipboardLockManager.runWithClipboardLock(() -> {
                try {
                    // 点击分享链接按钮
                    page.locator(
                            "(//div[contains(@class,'system-operation-box')])[last()]//div[" +
                                    "contains(@class,'md:hover:bg-col_bg03') and " +
                                    "contains(@class,'flex') and " +
                                    "contains(@class,'h-7') and " +
                                    "contains(@class,'w-7') and " +
                                    "contains(@class,'cursor-pointer') and " +
                                    "contains(@class,'items-center') and " +
                                    "contains(@class,'justify-center') and " +
                                    "contains(@class,'rounded-[8px]')" +
                                    "][3]"
                    ).click();
                    // 等待加载
                    Thread.sleep(1000);
                    page.locator("xpath=/html/body/section/div/div/section/div/div[1]/div/div/main/section/div[2]/div[1]/span[1]").click();
                    // 点击复制链接
                    page.locator("xpath=/html/body/section/div/div/section/div/div[1]/div/div/main/section/div[2]/div[2]/div[2]").click();
                    // 建议适当延迟等待内容更新
                    Thread.sleep(1000);
                    String shareUrl = (String) page.evaluate("navigator.clipboard.readText()");
                    shareUrlRef.set(shareUrl);
                    System.out.println("剪贴板内容：" + shareUrl);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            Thread.sleep(1000);
            String shareUrl = shareUrlRef.get();
            String sharImgUrl = "";
            // 点击分享按钮
            page.locator(
                    "(//div[contains(@class,'system-operation-box')])[last()]//div[" +
                            "contains(@class,'md:hover:bg-col_bg03') and " +
                            "contains(@class,'flex') and " +
                            "contains(@class,'h-7') and " +
                            "contains(@class,'w-7') and " +
                            "contains(@class,'cursor-pointer') and " +
                            "contains(@class,'items-center') and " +
                            "contains(@class,'justify-center') and " +
                            "contains(@class,'rounded-[8px]')" +
                            "][3]"
            ).click();
            Thread.sleep(1000);
            page.locator("xpath=/html/body/section/div/div/section/div/div[1]/div/div/main/section/div[2]/div[1]/span[1]").click();

            Thread.sleep(1000);
            // 点击生成分享图
            page.locator("xpath=/html/body/section/div/div/section/div/div[1]/div/div/main/section/div[2]/div[2]/div[1]").click();
            Thread.sleep(3000);
            // 点击下载按钮
            sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                page.locator("xpath=/html/body/section/div/div/section/div/div[1]/div/div/main/div[1]/div[2]/div/div[2]").click();
            });

            logInfo.sendTaskLog( "执行完成",userId,"MiniMax Chat");
            logInfo.sendChatData(page,"chatID=([0-9]+)",userId,"RETURN_MAX_CHATID",1);
            logInfo.sendResData(copiedText,userId,"MiniMax Chat","RETURN_MAX_RES",shareUrl,sharImgUrl);

            //保存数据库
            userInfoRequest.setDraftContent(copiedText);
            userInfoRequest.setAiName("MiniMax Chat");
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(sharImgUrl);
            RestUtils.post(url+"/saveDraftContent", userInfoRequest);
            return copiedText;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "获取内容失败";
    }

    /**
     * 处理豆包的常规请求
     * @param userInfoRequest 包含会话ID和用户指令
     * @return AI生成的文本内容
     */
    @Operation(summary = "启动豆包AI生成", description = "调用豆包AI平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDB")
    public String startDB(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest){
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false,userInfoRequest.getUserId(),"db")) {

            // 初始化变量
            String userId = userInfoRequest.getUserId();
            String dbchatId = userInfoRequest.getDbChatId();
            logInfo.sendTaskLog( "豆包准备就绪，正在打开页面",userId,"豆包");
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();

            // 初始化页面并导航到指定会话
            Page page = context.newPage();
            if(dbchatId!=null){
                page.navigate("https://www.doubao.com/chat/"+dbchatId);
            }else {
                page.navigate("https://www.doubao.com/chat/");
            }

            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(500);
            logInfo.sendTaskLog( "豆包页面打开完成",userId,"豆包");
            // 定位深度思考按钮
            Locator deepThoughtButton = page.locator("button.semi-button:has-text('深度思考')");
            // 检查按钮是否包含以 active- 开头的类名
            Boolean isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                    "    const classList = Array.from(element.classList);\n" +
                    "    return classList.some(cls => cls.startsWith('active-'));\n" +
                    "}");

            // 确保 isActive 不为 null
            if (isActive != null && !isActive && roles.contains("db-sdsk")) {
                deepThoughtButton.click();
                // 点击后等待一段时间，确保按钮状态更新
                Thread.sleep(1000);

                // 再次检查按钮状态
                isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                        "    const classList = Array.from(element.classList);\n" +
                        "    return classList.some(cls => cls.startsWith('active-'));\n" +
                        "}");
                if (isActive != null && !isActive) {
                    deepThoughtButton.click();
                    Thread.sleep(1000);
                }
                logInfo.sendTaskLog( "已启动深度思考模式",userId,"豆包");
            }
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").click();
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").fill(userPrompt);
            logInfo.sendTaskLog( "用户指令已自动输入完成",userId,"豆包");
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").press("Enter");
            logInfo.sendTaskLog( "指令已自动发送成功",userId,"豆包");

            // 创建定时截图线程
            AtomicInteger i = new AtomicInteger(0);
            ScheduledExecutorService screenshotExecutor = Executors.newSingleThreadScheduledExecutor();
            AtomicBoolean shouldStopScreenshot = new AtomicBoolean(false);
            
            // 启动定时任务，每5秒执行一次截图
            ScheduledFuture<?> screenshotFuture = screenshotExecutor.scheduleAtFixedRate(() -> {
                try {
                    // 检查是否应该停止截图
                    if (shouldStopScreenshot.get()) {
                        return;
                    }
                    
                    // 检查页面是否已关闭，如果页面关闭则退出
                    if (page == null || page.isClosed()) {
                        System.out.println("页面已关闭，停止豆包截图任务");
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    
                    // 检查浏览器上下文状态
                    try {
                        page.context().browser();
                    } catch (Exception e) {
                        System.out.println("浏览器上下文已关闭，停止豆包截图任务");
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    
                    int currentCount = i.getAndIncrement(); // 获取当前值并自增
                    logInfo.sendImgData(page, userId + "豆包执行过程截图"+currentCount, userId);
                } catch (com.microsoft.playwright.PlaywrightException e) {
                    // 处理Playwright特定异常
                    if (e.getMessage().contains("Object doesn't exist") || 
                        e.getMessage().contains("worker@") ||
                        e.getMessage().contains("Target closed") ||
                        e.getMessage().contains("Page closed")) {
                        System.out.println("页面或浏览器已关闭，停止豆包截图任务: " + e.getMessage());
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    System.out.println("豆包截图任务Playwright异常: " + e.getMessage());
                } catch (Exception e) {
                    System.out.println("豆包截图任务异常: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 0, 8, TimeUnit.SECONDS);

            logInfo.sendTaskLog( "开启自动监听任务，持续监听豆包回答中",userId,"豆包");
            // 等待复制按钮出现并点击
//            String copiedText =  douBaoUtil.waitAndClickDBCopyButton(page,userId,roles);
            //等待html片段获取完成
            String copiedText =  douBaoUtil.waitDBHtmlDom(page,userId,"豆包");
            
            // 停止截图任务
            shouldStopScreenshot.set(true);
            screenshotFuture.cancel(false);
            screenshotExecutor.shutdown();
            
            // 等待截图任务完全停止
            try {
                if (!screenshotExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    screenshotExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                screenshotExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            boolean isRight;

            Locator chatHis = page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/div/main/div/div/div[2]/div/div[1]/div/div/div[2]/div[2]/div/div/div/div/div/div/div[1]/div/div/div[2]/div[1]/div/div");
            if(chatHis.count()>0){
                isRight =true;
            } else {
                isRight = false;
            }

            AtomicReference<String> shareUrlRef = new AtomicReference<>();

            clipboardLockManager.runWithClipboardLock(() -> {
                try {
                    if(isRight && page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[4]").count()>0){
                        page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[4]").click();
                        Thread.sleep(1000);
                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("公开分享")).click();
                        Thread.sleep(500);
                    }else{
                        page.locator("button[data-testid='message_action_share']").last().click();
                        Thread.sleep(2000);
                        page.locator("button[data-testid='thread_share_copy_btn']").first().click();
                    }

                    // 建议适当延迟等待内容更新
                    Thread.sleep(2000);
                    String shareUrl = (String) page.evaluate("navigator.clipboard.readText()");
                    shareUrlRef.set(shareUrl);
                    System.out.println("剪贴板内容：" + shareUrl);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            Thread.sleep(1000);
            String shareUrl = shareUrlRef.get();
            String sharImgUrl = "";
            if (isRight && page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[3]").count()>0) {
                page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[3]").click();
                sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                    page.getByTestId("popover_select_option_item").nth(1).click();
                });
            } else {
                page.locator("button[data-testid='message_action_share']").last().click();
                Thread.sleep(2000);
                page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/div/main/div/div/div[3]/div/div/div/div/div[1]/div/div/button").click();
                Thread.sleep(5000);
                sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                    page.locator("button:has-text(\"下载图片\")").click();
                });
            }

            logInfo.sendTaskLog( "执行完成",userId,"豆包");
            logInfo.sendChatData(page,"/chat/([^/?#]+)",userId,"RETURN_DB_CHATID",1);
            logInfo.sendResData(copiedText,userId,"豆包","RETURN_DB_RES",shareUrl,sharImgUrl);

            //保存数据库
            userInfoRequest.setDraftContent(copiedText);
            userInfoRequest.setAiName("豆包");
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(sharImgUrl);
            RestUtils.post(url+"/saveDraftContent", userInfoRequest);
            return copiedText;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "获取内容失败";
    }


    /**
     * 处理知乎直答的请求
     * @param userInfoRequest 包含用户ID、角色、提示信息等
     * @return 知乎直答生成的内容
     */
    @Operation(summary = "启动知乎直答AI生成", description = "调用知乎直答AI平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startZH")
    public String startZH(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest) {
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false, userInfoRequest.getUserId(), "zhihu")) {
            // 初始化变量
            String userId = userInfoRequest.getUserId();
            String zhChatId = userInfoRequest.getZhChatId();
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();
            logInfo.sendTaskLog("知乎直答准备就绪，正在打开页面", userId, "知乎直答");

            // 初始化页面并导航到知乎直答指定页面
            Page page = context.newPage();
            if (zhChatId != null && !zhChatId.isEmpty()) {
                // 如果有会话ID，导航到特定会话页面
                page.navigate("https://zhida.zhihu.com/search/" + zhChatId);
                logInfo.sendTaskLog("已恢复页面到知乎直答会话"+zhChatId, userId, "知乎直答");
            } else {
                // 如果没有会话ID，导航到首页
                page.navigate("https://zhida.zhihu.com/search");
            }

            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(2000);
            logInfo.sendTaskLog("知乎直答页面打开完成", userId, "知乎直答");
            // 定位按钮
            Locator deepThinkButton = page.locator("[data-testid='Button:deep_thinking_button']");

            boolean isActiveForDT = true;
            // 定位到开关滑块
            Locator toggleSwitch = deepThinkButton.locator("div[style*='border-radius: 100px'] > div");
            // 获取 transform 属性
            String transform = toggleSwitch.getAttribute("style");
            // 判断是否开启
            isActiveForDT = transform != null && transform.contains("translateX(10px)");

            if(!isActiveForDT && roles.contains("zhihu-sdsk")) {
                Thread.sleep(1000);
               deepThinkButton.click();
               Thread.sleep(1000);
               logInfo.sendTaskLog("已开启深度思考",userId,"知乎直答");
            }
            if (isActiveForDT && !roles.contains("zhihu-sdsk")){
                Thread.sleep(1000);
                deepThinkButton.click();
                Thread.sleep(1000);
                logInfo.sendTaskLog("已关闭深度思考",userId,"知乎直答");
            }

            // 定位可编辑的 div 元素
            page.locator("div.notranslate.public-DraftEditor-content").click();
            Thread.sleep(1000);
            //输入用户指令
            page.locator("div.notranslate.public-DraftEditor-content").fill(userPrompt);
            logInfo.sendTaskLog("用户指令已自动输入完成", userId, "知乎直答");
            Thread.sleep(1000);
            // 点击发送按钮
            page.locator("div.css-175oi2r svg[fill='#ffffff'][width='20'][height='20']").click();
            logInfo.sendTaskLog("指令已自动发送成功", userId, "知乎直答");
            Thread.sleep(1000);

            // 创建定时截图线程
            AtomicInteger i = new AtomicInteger(0);
            ScheduledExecutorService screenshotExecutor = Executors.newSingleThreadScheduledExecutor();
            AtomicBoolean shouldStopScreenshot = new AtomicBoolean(false);

            // 启动定时任务，每8秒执行一次截图
            ScheduledFuture<?> screenshotFuture = screenshotExecutor.scheduleAtFixedRate(() -> {
                try {
                    // 检查是否应该停止截图
                    if (shouldStopScreenshot.get()) {
                        return;
                    }
                    
                    // 检查页面是否已关闭，如果页面关闭则退出
                    if (page == null || page.isClosed()) {
                        System.out.println("页面已关闭，停止截图任务");
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    
                    // 检查浏览器上下文状态
                    try {
                        page.context().browser();
                    } catch (Exception e) {
                        System.out.println("浏览器上下文已关闭，停止截图任务");
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    
                    int currentCount = i.getAndIncrement(); // 获取当前值并自增
                    logInfo.sendImgData(page, userId + "知乎直答执行过程截图"+currentCount, userId);
                } catch (com.microsoft.playwright.PlaywrightException e) {
                    // 处理Playwright特定异常
                    if (e.getMessage().contains("Object doesn't exist") || 
                        e.getMessage().contains("worker@") ||
                        e.getMessage().contains("Target closed") ||
                        e.getMessage().contains("Page closed")) {
                        System.out.println("页面或浏览器已关闭，停止截图任务: " + e.getMessage());
                        shouldStopScreenshot.set(true);
                        return;
                    }
                    System.out.println("截图任务Playwright异常: " + e.getMessage());
                } catch (Exception e) {
                    System.out.println("截图任务异常: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 0, 8, TimeUnit.SECONDS);

            logInfo.sendTaskLog("开启自动监听任务，持续监听知乎直答回答中", userId, "知乎直答");

            // 等待回复完成 - 使用zhiHuUtil中的方法等待回复
            String responseText = zhiHuUtil.waitZHResponse(page, userId,"知乎直答");

            // 停止截图任务
            shouldStopScreenshot.set(true);
            screenshotFuture.cancel(false);
            screenshotExecutor.shutdown();
            
            // 等待截图任务完全停止
            try {
                if (!screenshotExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    screenshotExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                screenshotExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // 处理知乎直答的分享流程，获取分享后的内容
            String[] shareResults = zhiHuUtil.handleZhihuShare(page, userId, uploadUrl);
            String shareUrl = shareResults[0];
            String shareImgUrl = shareResults[1];

            // 返回执行结果
            logInfo.sendTaskLog("执行完成", userId, "知乎直答");
            //发送会话ID
            logInfo.sendChatData(page, "/search/([^/?#]+)", userId, "RETURN_ZH_CHATID", 1);
            logInfo.sendResData(responseText, userId, "知乎直答", "RETURN_ZH_RES", shareUrl, shareImgUrl);

            // 保存数据库
            userInfoRequest.setDraftContent(responseText);
            userInfoRequest.setAiName("知乎直答");
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(shareImgUrl);
            RestUtils.post(url + "/saveDraftContent", userInfoRequest);

            return responseText;
        } catch (Exception e) {
            e.printStackTrace();
            String errorMsg = "知乎直答处理出错: " + e.getMessage();
            logInfo.sendTaskLog(errorMsg, userInfoRequest.getUserId(), "知乎直答");
            logInfo.sendResData(errorMsg, userInfoRequest.getUserId(), "知乎直答", "RETURN_ZH_RES", "", "");
        }
        return "获取内容失败";
    }


    @Operation(summary = "豆包智能评分", description = "调用豆包平台对内容进行评分并返回评分结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDBScore")
    public String startDBScore(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest){
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false,userInfoRequest.getUserId(),"db")) {

            // 初始化变量
            String userId = userInfoRequest.getUserId();
            logInfo.sendTaskLog( "评分准备就绪，正在打开页面",userId,"智能评分");
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();

            // 初始化页面并导航到指定会话
            Page page = context.newPage();
            page.navigate("https://www.doubao.com/chat/");
            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(500);
            logInfo.sendTaskLog( "智能评分页面打开完成",userId,"智能评分");
            // 定位深度思考按钮
            Locator deepThoughtButton = page.locator("button.semi-button:has-text('深度思考')");
            // 检查按钮是否包含以 active- 开头的类名
            Boolean isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                    "    const classList = Array.from(element.classList);\n" +
                    "    return classList.some(cls => cls.startsWith('active-'));\n" +
                    "}");

            // 确保 isActive 不为 null
            if (isActive != null && !isActive && roles.contains("db-sdsk")) {
                deepThoughtButton.click();
                // 点击后等待一段时间，确保按钮状态更新
                Thread.sleep(1000);

                // 再次检查按钮状态
                isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                        "    const classList = Array.from(element.classList);\n" +
                        "    return classList.some(cls => cls.startsWith('active-'));\n" +
                        "}");
                if (isActive != null && !isActive) {
                    deepThoughtButton.click();
                    Thread.sleep(1000);
                }
            }
            Thread.sleep(500);
            page.locator("[data-testid='chat_input_input']").click();
            Thread.sleep(500);
            page.locator("[data-testid='chat_input_input']").fill(userPrompt);
            logInfo.sendTaskLog( "初稿已录入评分系统完成",userId,"智能评分");
            Thread.sleep(500);
            page.locator("[data-testid='chat_input_input']").press("Enter");

            // 创建安全的截图任务
            ScreenshotTaskManager screenshotTask = createSafeScreenshotTask(page, userId, "评分");

            logInfo.sendTaskLog( "开启自动监听任务，持续监听评分结果",userId,"智能评分");
            // 等待复制按钮出现并点击
            String copiedText =  douBaoUtil.waitDBHtmlDom(page,userId,"智能评分");

            // 停止截图任务
            screenshotTask.stop();
            
            boolean isRight;
            Locator chatHis = page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/div/main/div/div/div[2]/div/div[1]/div/div/div[2]/div[2]/div/div/div/div/div/div/div[1]/div/div/div[2]/div[1]/div/div");
            if(chatHis.count()>0){
                isRight =true;
            } else {
                isRight = false;
            }

            AtomicReference<String> shareUrlRef = new AtomicReference<>();
            clipboardLockManager.runWithClipboardLock(() -> {
                try {
                    if(isRight && page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[4]").count()>0){
                        page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[4]").click();
                        Thread.sleep(1000);
                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("公开分享")).click();
                        Thread.sleep(500);
                    }else{
                        page.locator("button[data-testid='message_action_share']").last().click();
                        Thread.sleep(2000);
                        page.locator("button[data-testid='thread_share_copy_btn']").first().click();
                    }

                    // 建议适当延迟等待内容更新
                    Thread.sleep(2000);
                    String shareUrl = (String) page.evaluate("navigator.clipboard.readText()");
                    shareUrlRef.set(shareUrl);
                    System.out.println("剪贴板内容：" + shareUrl);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            Thread.sleep(1000);
            String shareUrl = shareUrlRef.get();
            String sharImgUrl = "";
            if (isRight && page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[3]").count()>0) {
                page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[3]").click();
                sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                    page.getByTestId("popover_select_option_item").nth(1).click();
                });
            } else {
                page.locator("button[data-testid='message_action_share']").last().click();
                Thread.sleep(2000);
                page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/div/main/div/div/div[3]/div/div/div/div/div[1]/div/div/button").click();
                Thread.sleep(5000);
                sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                    page.locator("button:has-text(\"下载图片\")").click();
                });
            }

            logInfo.sendTaskLog( "执行完成",userId,"智能评分");
            logInfo.sendResData(copiedText,userId,"智能评分","RETURN_WKPF_RES",shareUrl,sharImgUrl);
            userInfoRequest.setDraftContent(copiedText);
            userInfoRequest.setAiName("智能评分");
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(sharImgUrl);
            RestUtils.post(url+"/saveDraftContent", userInfoRequest);
            return copiedText;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "获取内容失败";
    }


    @Operation(summary = "投递公众号排版", description = "调用豆包平台对内容进行评分并返回评分结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDBOffice")
    public String startDBOffice(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest){
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false,userInfoRequest.getUserId(),"db")) {
            // 初始化变量
            String userId = userInfoRequest.getUserId();
            logInfo.sendTaskLog( "智能排版准备就绪，正在打开页面",userId,"智能排版");
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();

            // 初始化页面并导航到指定会话
            Page page = context.newPage();
            page.navigate("https://www.doubao.com/chat/");
            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(500);
            logInfo.sendTaskLog( "智能排版页面打开完成",userId,"智能排版");
            // 定位深度思考按钮
            Locator deepThoughtButton = page.locator("button.semi-button:has-text('深度思考')");
            // 检查按钮是否包含以 active- 开头的类名
            Boolean isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                    "    const classList = Array.from(element.classList);\n" +
                    "    return classList.some(cls => cls.startsWith('active-'));\n" +
                    "}");

            // 确保 isActive 不为 null
            if (isActive != null && !isActive && roles.contains("db-sdsk")) {
                deepThoughtButton.click();
                // 点击后等待一段时间，确保按钮状态更新
                Thread.sleep(1000);

                // 再次检查按钮状态
                isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                        "    const classList = Array.from(element.classList);\n" +
                        "    return classList.some(cls => cls.startsWith('active-'));\n" +
                        "}");
                if (isActive != null && !isActive) {
                    deepThoughtButton.click();
                    Thread.sleep(1000);
                }
            }
            Thread.sleep(500);
            page.locator("[data-testid='chat_input_input']").click();
            Thread.sleep(500);
            page.locator("[data-testid='chat_input_input']").fill(userPrompt);
            logInfo.sendTaskLog( "原数据已录入智能排版系统完成",userId,"智能排版");
            Thread.sleep(500);
            page.locator("[data-testid='chat_input_input']").press("Enter");

            // 创建安全的截图任务
            ScreenshotTaskManager screenshotTask = createSafeScreenshotTask(page, userId, "智能排版");

            logInfo.sendTaskLog( "开启自动监听任务，持续监听智能排版结果",userId,"智能排版");
            // 等待复制按钮出现并点击
            String copiedText =  douBaoUtil.waitPBCopy(page,userId,"智能排版");

            // 停止截图任务
            screenshotTask.stop();

            logInfo.sendTaskLog( "执行完成",userId,"智能排版");
            logInfo.sendResData(copiedText,userId,"智能排版","RETURN_ZNPB_RES","","");
            userInfoRequest.setDraftContent(copiedText);
            userInfoRequest.setAiName("智能评分");
            userInfoRequest.setShareUrl("");
            userInfoRequest.setShareImgUrl("");
            RestUtils.post(url+"/saveDraftContent", userInfoRequest);
            return copiedText;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "获取内容失败";
    }



    /**
     * 处理千问的常规请求
     * @param userInfoRequest 包含会话ID和用户指令
     * @return AI生成的文本内容
     */
    @Operation(summary = "启动千问AI生成", description = "调用千问AI平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startQW")
    public String startQW(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest){
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false,userInfoRequest.getUserId(),"qwen")) {

            // 初始化变量
            String userId = userInfoRequest.getUserId();
            String qwchatId = userInfoRequest.getQwChatId();
            logInfo.sendTaskLog( "通义千问准备就绪，正在打开页面",userId,"通义千问");
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();

            // 初始化页面并导航到指定会话
            Page page = context.newPage();
            if(qwchatId!=null){
                page.navigate("https://www.tongyi.com/qianwen?sessionId="+qwchatId);
            }else {
                page.navigate("https://www.tongyi.com/qianwen");
            }

            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(500);
            logInfo.sendTaskLog( "通义千问页面打开完成",userId,"通义千问");
            // 定位深度思考按钮

            // 确保 isActive 不为 null
//            if (roles.contains("qw-sdsk")) {
//                page.locator("//*[@id=\"ice-container\"]/div/div/div[2]/div/div[2]/div[1]/div[3]/div[1]/div[1]/div[2]").click();
//                // 点击后等待一段时间，确保按钮状态更新
//                Thread.sleep(1000);
//
//                logInfo.sendTaskLog( "已启动深度思考模式",userId,"通义千问");
//                page.locator("//*[@id=\"ice-container\"]/div/div/div[2]/div/div[2]/div[1]/div[3]/div/div[2]/div[2]/div/textarea").click();
//                Thread.sleep(1000);
//                page.locator("//*[@id=\"ice-container\"]/div/div/div[2]/div/div[2]/div[1]/div[3]/div/div[2]/div[2]/div/textarea").fill(userPrompt);
//                logInfo.sendTaskLog( "用户指令已自动输入完成",userId,"通义千问");
//                Thread.sleep(1000);
//                page.locator("//*[@id=\"ice-container\"]/div/div/div[2]/div/div[2]/div[1]/div[3]/div/div[2]/div[2]/div/textarea").press("Enter");
//                logInfo.sendTaskLog( "指令已自动发送成功",userId,"通义千问");
//            }else{
//                page.locator("//*[@id=\"ice-container\"]/div/div/div[2]/div/div[2]/div[1]/div[3]/div[2]/div/div[2]/div/textarea").click();
//                Thread.sleep(1000);
//                page.locator("//*[@id=\"ice-container\"]/div/div/div[2]/div/div[2]/div[1]/div[3]/div[2]/div/div[2]/div/textarea").fill(userPrompt);
//                logInfo.sendTaskLog( "用户指令已自动输入完成",userId,"通义千问");
//                Thread.sleep(1000);
//                page.locator("//*[@id=\"ice-container\"]/div/div/div[2]/div/div[2]/div[1]/div[3]/div[2]/div/div[2]/div/textarea").press("Enter");
//                logInfo.sendTaskLog( "指令已自动发送成功",userId,"通义千问");
//            }
            Thread.sleep(1000);

            // 创建安全的截图任务
            ScreenshotTaskManager screenshotTask = createSafeScreenshotTask(page, userId, "通义千问");

            logInfo.sendTaskLog( "开启自动监听任务，持续监听通义千问回答中",userId,"通义千问");
            // 等待复制按钮出现并点击
//            String copiedText =  douBaoUtil.waitAndClickDBCopyButton(page,userId,roles);
            //等待html片段获取完成
            String copiedText =  qwenUtil.waitQWHtmlDom(page,userId);
            
            // 停止截图任务
            screenshotTask.stop();


            AtomicReference<String> shareUrlRef = new AtomicReference<>();

            clipboardLockManager.runWithClipboardLock(() -> {
                try {
                    Locator allShareIcons = page.locator("use[xlink\\:href='#tongyi-share-line']");
                    int count = allShareIcons.count();
                    allShareIcons.nth(count - 1).locator("xpath=../../..").click();
                    Thread.sleep(2000);
                    page.locator("//*[@id=\"ice-container\"]/div/div/div[2]/div/div[2]/div[1]/div[3]/button[2]").click();
                    // 建议适当延迟等待内容更新
                    Thread.sleep(2000);
                    page.getByText("复制链接").last().click();
                    String shareUrl = (String) page.evaluate("navigator.clipboard.readText()");
                    shareUrlRef.set(shareUrl);
                    System.out.println("剪贴板内容：" + shareUrl);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            Thread.sleep(1000);
            String shareUrl = shareUrlRef.get();

            logInfo.sendTaskLog( "执行完成",userId,"通义千问");
            logInfo.sendChatData(page,"/chat/([^/?#]+)",userId,"RETURN_DB_CHATID",1);
            logInfo.sendResData(copiedText,userId,"通义千问","RETURN_DB_RES",shareUrl,null);

            //保存数据库
            userInfoRequest.setDraftContent(copiedText);
            userInfoRequest.setAiName("通义千问");
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(null);
            RestUtils.post(url+"/saveDraftContent", userInfoRequest);
            return copiedText;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "获取内容失败";
    }


    /**
     * 处理DeepSeek的常规请求
     * @param userInfoRequest 包含会话ID和用户指令
     * @return AI生成的文本内容
     */
    @Operation(summary = "启动DeepSeek AI生成", description = "调用DeepSeek AI平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDeepSeek")
    public String startDeepSeek(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest) {
        // 添加全局异常处理
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false, userInfoRequest.getUserId(), "deepseek")) {
            // 设置全局超时时间，提高稳定性
            context.setDefaultTimeout(60000); // 60秒
            // 初始化变量
            String userId = userInfoRequest.getUserId();
            logInfo.sendTaskLog("DeepSeek准备就绪，正在打开页面", userId, "DeepSeek");
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();
            String chatId = userInfoRequest.getYbDsChatId(); // 获取会话ID
            String isNewChat = userInfoRequest.getIsNewChat(); // 是否新会话

            // 如果指定了新会话，则忽略已有的会话ID
            if ("true".equalsIgnoreCase(isNewChat)) {
                System.out.println("用户请求新会话，将忽略已有会话ID");
                chatId = null;
            } else if (chatId != null && !chatId.isEmpty()) {
                logInfo.sendTaskLog("检测到会话ID: " + chatId + "，将继续使用此会话", userId, "DeepSeek");
            } else {
                System.out.println("未检测到会话ID，将创建新会话");
            }

            // 初始化页面并发送消息
            Page page = context.newPage();

            // 设置页面超时时间更长
            page.setDefaultTimeout(60000); // 60秒

            // 创建安全的截图任务
            ScreenshotTaskManager screenshotTask = createSafeScreenshotTask(page, userId, "DeepSeek");

            logInfo.sendTaskLog("开启自动监听任务，持续监听DeepSeek回答中", userId, "DeepSeek");

            // 发送消息并获取回答
            String copiedText = "";
            int maxRetries = 3;

            // 重试循环
            for (int retry = 0; retry < maxRetries; retry++) {
                try {
                    if (retry > 0) {
                                                        // 刷新页面重新开始
                                page.reload();
                                page.waitForLoadState(LoadState.LOAD);
                                Thread.sleep(2000);
                    }

                    copiedText = deepSeekUtil.handleDeepSeekAI(page, userPrompt, userId, roles, chatId);

                    if (!copiedText.startsWith("获取内容失败") && !copiedText.isEmpty()) {
                        break; // 成功获取内容，跳出重试循环
                    }

                    Thread.sleep(3000); // 等待3秒后重试
                } catch (Exception e) {
                    if (retry == maxRetries - 1) {
                        copiedText = "获取内容失败：多次尝试后仍然失败";
                    }
                    Thread.sleep(2000); // 出错后等待2秒
                }
            }

            // 停止截图任务
            screenshotTask.stop();

            // 如果获取内容失败，尝试从页面中提取任何可能的内容
            if (copiedText.startsWith("获取内容失败") || copiedText.isEmpty()) {
                try {
                    System.out.println("尝试使用备用方法提取内容");

                    // 使用JavaScript提取页面上的任何文本内容
                    Object extractedContent = page.evaluate("""
                        () => {
                            // 尝试查找任何可能包含回复的元素
                            const contentElements = document.querySelectorAll('.ds-markdown, .flow-markdown-body, .message-content, .ds-markdown-paragraph');
                            if (contentElements.length > 0) {
                                // 获取最后一个元素的文本
                                const lastElement = contentElements[contentElements.length - 1];
                                return lastElement.innerHTML || lastElement.innerText || '';
                            }

                            // 如果找不到特定元素，尝试获取页面上的任何文本
                            const bodyText = document.body.innerText;
                            if (bodyText && bodyText.length > 50) {
                                return bodyText;
                            }

                            return '无法提取内容';
                        }
                    """);

                    if (extractedContent != null && !extractedContent.toString().isEmpty() &&
                            !extractedContent.toString().equals("无法提取内容")) {
                        copiedText = extractedContent.toString();
                        System.out.println("使用备用方法成功提取内容");
                    }
                } catch (Exception e) {
                    System.out.println("备用提取方法失败: " + e.getMessage());
                }
            }

            // 添加额外的HTML格式化处理
            try {
                // 检查是否需要额外的格式化
                if (!copiedText.startsWith("获取内容失败") && !copiedText.isEmpty()) {
                    // 使用JavaScript在浏览器中进行最终的格式化处理
                    Object finalFormattedContent = page.evaluate("""
                        (content) => {
                            try {
                                // 检查内容是否已经是HTML格式
                                const isHtml = content.trim().startsWith('<') && content.includes('</');

                                // 如果不是HTML格式，进行基本的HTML转换
                                if (!isHtml) {
                                    // 将换行符转换为<br>标签
                                    content = content.replace(/\\n/g, '<br>');

                                    // 检测并转换Markdown风格的链接 [text](url)
                                    content = content.replace(/\\[([^\\]]+)\\]\\(([^)]+)\\)/g, '<a href="$2" target="_blank" style="color: #0066cc; text-decoration: none;">$1</a>');

                                    // 检测并转换Markdown风格的加粗文本 **text**
                                    content = content.replace(/\\*\\*([^*]+)\\*\\*/g, '<strong>$1</strong>');

                                    // 检测并转换Markdown风格的斜体文本 *text*
                                    content = content.replace(/\\*([^*]+)\\*/g, '<em>$1</em>');

                                    // 检测并转换Markdown风格的代码块 ```code```
                                    content = content.replace(/```([\\s\\S]+?)```/g, '<pre style="background-color: #f5f5f5; padding: 10px; border-radius: 4px; font-family: monospace; overflow-x: auto; display: block; margin: 10px 0;">$1</pre>');

                                    // 检测并转换Markdown风格的行内代码 `code`
                                    content = content.replace(/`([^`]+)`/g, '<code style="background-color: #f5f5f5; padding: 2px 4px; border-radius: 3px; font-family: monospace;">$1</code>');
                                }

                                // 创建一个包含样式的容器
                                const styledContainer = document.createElement('div');
                                styledContainer.className = 'deepseek-response';
                                styledContainer.style.cssText = 'max-width: 800px; margin: 0 auto; background-color: #fff; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); padding: 20px; font-family: Arial, sans-serif; line-height: 1.6; color: #333;';

                                // 添加内容
                                styledContainer.innerHTML = content;

                                // 返回完整的HTML
                                return styledContainer.outerHTML;
                            } catch (e) {
                                console.error('最终格式化内容时出错:', e);
                                return content; // 出错时返回原始内容
                            }
                        }
                    """, copiedText);

                    if (finalFormattedContent != null && !finalFormattedContent.toString().isEmpty()) {
                        copiedText = finalFormattedContent.toString();
                    }
                }
            } catch (Exception e) {
                System.out.println("最终格式化处理失败: " + e.getMessage());
            }

            // 保存结果
            try {
                copiedText = deepSeekUtil.saveDeepSeekContent(page, userInfoRequest, roles, userId, copiedText);
            } catch (Exception e) {
                System.out.println("保存DeepSeek内容到稿库失败: " + e.getMessage());
                e.printStackTrace();
            }
            return copiedText;
        } catch (Exception e) {
            e.printStackTrace();
            return "获取内容失败: " + e.getMessage();
        }
    }

}
