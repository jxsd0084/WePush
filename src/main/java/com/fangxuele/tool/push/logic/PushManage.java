package com.fangxuele.tool.push.logic;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.bean.WxMaTemplateData;
import cn.binarywang.wx.miniapp.bean.WxMaTemplateMessage;
import cn.binarywang.wx.miniapp.config.WxMaInMemoryConfig;
import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsRequest;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.fangxuele.tool.push.ui.Init;
import com.fangxuele.tool.push.ui.MainWindow;
import com.fangxuele.tool.push.util.SystemUtil;
import com.fangxuele.tool.push.util.TemplateUtil;
import com.github.qcloudsms.SmsSingleSender;
import com.github.qcloudsms.SmsSingleSenderResult;
import com.opencsv.CSVWriter;
import com.taobao.api.DefaultTaobaoClient;
import com.taobao.api.TaobaoClient;
import com.taobao.api.request.AlibabaAliqinFcSmsNumSendRequest;
import com.taobao.api.response.AlibabaAliqinFcSmsNumSendResponse;
import com.yunpian.sdk.YunpianClient;
import com.yunpian.sdk.model.Result;
import com.yunpian.sdk.model.SmsSingleSend;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpConfigStorage;
import me.chanjar.weixin.mp.api.WxMpInMemoryConfigStorage;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateData;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateMessage;
import org.apache.commons.lang3.StringUtils;
import org.apache.velocity.VelocityContext;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 推送管理
 * Created by rememberber(https://github.com/rememberber) on 2017/6/19.
 */
public class PushManage {

    private static final Log logger = LogFactory.get();

    /**
     * 模板变量前缀
     */
    public static final String TEMPLATE_VAR_PREFIX = "var";

    /**
     * 预览消息
     *
     * @throws Exception 异常
     */
    public static boolean preview() throws Exception {
        List<String[]> msgDataList = new ArrayList<>();

        for (String data : MainWindow.mainWindow.getPreviewUserField().getText().split(";")) {
            msgDataList.add(data.split(","));
        }

        switch (Objects.requireNonNull(MainWindow.mainWindow.getMsgTypeComboBox().getSelectedItem()).toString()) {
            case "模板消息":
                WxMpTemplateMessage wxMessageTemplate;
                WxMpService wxMpService = getWxMpService();
                if (wxMpService.getWxMpConfigStorage() == null) {
                    return false;
                }

                for (String[] msgData : msgDataList) {
                    wxMessageTemplate = makeMpTemplateMessage(msgData);
                    wxMessageTemplate.setToUser(msgData[0].trim());
                    // ！！！发送模板消息！！！
                    wxMpService.getTemplateMsgService().sendTemplateMsg(wxMessageTemplate);
                }
                break;
            case "模板消息-小程序":
                WxMaTemplateMessage wxMaMessageTemplate;
                WxMaService wxMaService = getWxMaService();
                if (wxMaService.getWxMaConfig() == null) {
                    return false;
                }

                for (String[] msgData : msgDataList) {
                    wxMaMessageTemplate = makeMaTemplateMessage(msgData);
                    wxMaMessageTemplate.setToUser(msgData[0].trim());
                    wxMaMessageTemplate.setFormId(msgData[1].trim());
                    // ！！！发送小程序模板消息！！！
                    wxMaService.getMsgService().sendTemplateMsg(wxMaMessageTemplate);
                }
                break;
            case "客服消息":
                wxMpService = getWxMpService();
                WxMpKefuMessage wxMpKefuMessage;
                if (wxMpService.getWxMpConfigStorage() == null) {
                    return false;
                }

                for (String[] msgData : msgDataList) {
                    wxMpKefuMessage = makeKefuMessage(msgData);
                    wxMpKefuMessage.setToUser(msgData[0]);
                    // ！！！发送客服消息！！！
                    wxMpService.getKefuService().sendKefuMessage(wxMpKefuMessage);
                }
                break;
            case "客服消息优先":
                wxMpService = getWxMpService();
                if (wxMpService.getWxMpConfigStorage() == null) {
                    return false;
                }

                for (String[] msgData : msgDataList) {
                    try {
                        wxMpKefuMessage = makeKefuMessage(msgData);
                        wxMpKefuMessage.setToUser(msgData[0]);
                        // ！！！发送客服消息！！！
                        wxMpService.getKefuService().sendKefuMessage(wxMpKefuMessage);
                    } catch (Exception e) {
                        wxMessageTemplate = makeMpTemplateMessage(msgData);
                        wxMessageTemplate.setToUser(msgData[0].trim());
                        // ！！！发送模板消息！！！
                        wxMpService.getTemplateMsgService().sendTemplateMsg(wxMessageTemplate);
                    }
                }
                break;
            case "阿里云短信":
                String aliyunAccessKeyId = Init.configer.getAliyunAccessKeyId();
                String aliyunAccessKeySecret = Init.configer.getAliyunAccessKeySecret();

                if (StringUtils.isEmpty(aliyunAccessKeyId) || StringUtils.isEmpty(aliyunAccessKeySecret)) {
                    JOptionPane.showMessageDialog(MainWindow.mainWindow.getSettingPanel(),
                            "请先在设置中填写并保存阿里云短信相关配置！", "提示",
                            JOptionPane.INFORMATION_MESSAGE);
                    return false;
                }

                //初始化acsClient,暂不支持region化
                IClientProfile profile = DefaultProfile.getProfile("cn-hangzhou", aliyunAccessKeyId, aliyunAccessKeySecret);
                DefaultProfile.addEndpoint("cn-hangzhou", "Dysmsapi", "cn-hangzhou");

                IAcsClient acsClient = new DefaultAcsClient(profile);
                for (String[] msgData : msgDataList) {
                    SendSmsRequest request = makeAliyunMessage(msgData);
                    request.setPhoneNumbers(msgData[0]);
                    SendSmsResponse response = acsClient.getAcsResponse(request);

                    if (response.getCode() == null || !"OK".equals(response.getCode())) {
                        throw new Exception(response.getMessage() + ";\n\nErrorCode:" +
                                response.getCode() + ";\n\ntelNum:" + msgData[0]);
                    }
                }
                break;
            case "腾讯云短信":
                String txyunAppId = Init.configer.getTxyunAppId();
                String txyunAppKey = Init.configer.getTxyunAppKey();

                if (StringUtils.isEmpty(txyunAppId) || StringUtils.isEmpty(txyunAppKey)) {
                    JOptionPane.showMessageDialog(MainWindow.mainWindow.getSettingPanel(),
                            "请先在设置中填写并保存腾讯云短信相关配置！", "提示",
                            JOptionPane.INFORMATION_MESSAGE);
                    return false;
                }

                SmsSingleSender ssender = new SmsSingleSender(Integer.valueOf(txyunAppId), txyunAppKey);

                for (String[] msgData : msgDataList) {
                    String[] params = makeTxyunMessage(msgData);
                    SmsSingleSenderResult result = ssender.sendWithParam("86", msgData[0],
                            Integer.valueOf(MainWindow.mainWindow.getMsgTemplateIdTextField().getText()),
                            params, Init.configer.getAliyunSign(), "", "");
                    if (result.result != 0) {
                        throw new Exception(result.toString());
                    }
                }
                break;
            case "阿里大于模板短信":
                String aliServerUrl = Init.configer.getAliServerUrl();
                String aliAppKey = Init.configer.getAliAppKey();
                String aliAppSecret = Init.configer.getAliAppSecret();

                if (StringUtils.isEmpty(aliServerUrl) || StringUtils.isEmpty(aliAppKey)
                        || StringUtils.isEmpty(aliAppSecret)) {
                    JOptionPane.showMessageDialog(MainWindow.mainWindow.getSettingPanel(),
                            "请先在设置中填写并保存阿里大于相关配置！", "提示",
                            JOptionPane.INFORMATION_MESSAGE);
                    return false;
                }

                TaobaoClient client = new DefaultTaobaoClient(aliServerUrl, aliAppKey, aliAppSecret);
                for (String[] msgData : msgDataList) {
                    AlibabaAliqinFcSmsNumSendRequest request = makeAliTemplateMessage(msgData);
                    request.setRecNum(msgData[0]);
                    AlibabaAliqinFcSmsNumSendResponse response = client.execute(request);
                    if (response.getResult() == null || !response.getResult().getSuccess()) {
                        throw new Exception(response.getBody() + ";\n\nErrorCode:" +
                                response.getErrorCode() + ";\n\ntelNum:" + msgData[0]);
                    }
                }
                break;
            case "云片网短信":
                String yunpianApiKey = Init.configer.getYunpianApiKey();

                if (StringUtils.isEmpty(yunpianApiKey)) {
                    JOptionPane.showMessageDialog(MainWindow.mainWindow.getSettingPanel(),
                            "请先在设置中填写并保存云片网短信相关配置！", "提示",
                            JOptionPane.INFORMATION_MESSAGE);
                    return false;
                }

                YunpianClient clnt = new YunpianClient(yunpianApiKey).init();

                for (String[] msgData : msgDataList) {
                    Map<String, String> params = makeYunpianMessage(msgData);
                    params.put(YunpianClient.MOBILE, msgData[0]);
                    Result<SmsSingleSend> result = clnt.sms().single_send(params);
                    if (result.getCode() != 0) {
                        throw new Exception(result.toString());
                    }
                }
                clnt.close();
                break;
            default:
                break;
        }
        return true;
    }

    /**
     * 组织模板消息-公众号
     *
     * @param msgData 消息数据
     * @return WxMpTemplateMessage
     */
    synchronized static WxMpTemplateMessage makeMpTemplateMessage(String[] msgData) {
        // 拼模板
        WxMpTemplateMessage wxMessageTemplate = WxMpTemplateMessage.builder().build();
        wxMessageTemplate.setTemplateId(MainWindow.mainWindow.getMsgTemplateIdTextField().getText().trim());
        wxMessageTemplate.setUrl(MainWindow.mainWindow.getMsgTemplateUrlTextField().getText().trim());

        String appid = MainWindow.mainWindow.getMsgTemplateMiniAppidTextField().getText().trim();
        String pagePath = MainWindow.mainWindow.getMsgTemplateMiniPagePathTextField().getText().trim();

        VelocityContext velocityContext = new VelocityContext();
        for (int i = 0; i < msgData.length; i++) {
            velocityContext.put(TEMPLATE_VAR_PREFIX + i, msgData[i]);
        }
        pagePath = TemplateUtil.evaluate(pagePath, velocityContext);
        WxMpTemplateMessage.MiniProgram miniProgram = new WxMpTemplateMessage.MiniProgram(appid, pagePath, true);
        wxMessageTemplate.setMiniProgram(miniProgram);

        if (MainWindow.mainWindow.getTemplateMsgDataTable().getModel().getRowCount() == 0) {
            Init.initTemplateDataTable();
        }

        DefaultTableModel tableModel = (DefaultTableModel) MainWindow.mainWindow.getTemplateMsgDataTable().getModel();
        int rowCount = tableModel.getRowCount();
        for (int i = 0; i < rowCount; i++) {
            String name = ((String) tableModel.getValueAt(i, 0)).trim();

            String value = ((String) tableModel.getValueAt(i, 1));
            value = TemplateUtil.evaluate(value, velocityContext);

            String color = ((String) tableModel.getValueAt(i, 2)).trim();
            WxMpTemplateData templateData = new WxMpTemplateData(name, value, color);
            wxMessageTemplate.addData(templateData);
        }

        return wxMessageTemplate;
    }

    /**
     * 组织模板消息-小程序
     *
     * @param msgData 消息信息
     * @return WxMaTemplateMessage
     */
    synchronized static WxMaTemplateMessage makeMaTemplateMessage(String[] msgData) {
        // 拼模板
        WxMaTemplateMessage wxMessageTemplate = WxMaTemplateMessage.builder().build();
        wxMessageTemplate.setTemplateId(MainWindow.mainWindow.getMsgTemplateIdTextField().getText().trim());
        wxMessageTemplate.setPage(MainWindow.mainWindow.getMsgTemplateUrlTextField().getText().trim());
        wxMessageTemplate.setEmphasisKeyword(MainWindow.mainWindow.getMsgTemplateKeyWordTextField().getText().trim() + ".DATA");

        if (MainWindow.mainWindow.getTemplateMsgDataTable().getModel().getRowCount() == 0) {
            Init.initTemplateDataTable();
        }

        DefaultTableModel tableModel = (DefaultTableModel) MainWindow.mainWindow.getTemplateMsgDataTable().getModel();
        int rowCount = tableModel.getRowCount();

        VelocityContext velocityContext = new VelocityContext();
        for (int i = 0; i < msgData.length; i++) {
            velocityContext.put(TEMPLATE_VAR_PREFIX + i, msgData[i]);
        }
        for (int i = 0; i < rowCount; i++) {
            String name = ((String) tableModel.getValueAt(i, 0)).trim();

            String value = ((String) tableModel.getValueAt(i, 1));
            value = TemplateUtil.evaluate(value, velocityContext);

            String color = ((String) tableModel.getValueAt(i, 2)).trim();
            WxMaTemplateData templateData = new WxMaTemplateData(name, value, color);
            wxMessageTemplate.addData(templateData);
        }

        return wxMessageTemplate;
    }

    /**
     * 组织客服消息
     *
     * @param msgData 消息信息
     * @return WxMpKefuMessage
     */
    synchronized static WxMpKefuMessage makeKefuMessage(String[] msgData) {

        WxMpKefuMessage kefuMessage = null;
        VelocityContext velocityContext = new VelocityContext();
        for (int i = 0; i < msgData.length; i++) {
            velocityContext.put(TEMPLATE_VAR_PREFIX + i, msgData[i]);
        }
        if ("图文消息".equals(Objects.requireNonNull(MainWindow.mainWindow.getMsgKefuMsgTypeComboBox().getSelectedItem()).toString())) {
            WxMpKefuMessage.WxArticle article = new WxMpKefuMessage.WxArticle();

            // 标题
            String title = MainWindow.mainWindow.getMsgKefuMsgTitleTextField().getText();
            title = TemplateUtil.evaluate(title, velocityContext);
            article.setTitle(title);

            // 图片url
            article.setPicUrl(MainWindow.mainWindow.getMsgKefuPicUrlTextField().getText());

            // 描述
            String description = MainWindow.mainWindow.getMsgKefuDescTextField().getText();
            description = TemplateUtil.evaluate(description, velocityContext);
            article.setDescription(description);

            // 跳转url
            article.setUrl(MainWindow.mainWindow.getMsgKefuUrlTextField().getText());

            kefuMessage = WxMpKefuMessage.NEWS().addArticle(article).build();
        } else if ("文本消息".equals(MainWindow.mainWindow.getMsgKefuMsgTypeComboBox().getSelectedItem().toString())) {
            String content = MainWindow.mainWindow.getMsgKefuMsgTitleTextField().getText();
            content = TemplateUtil.evaluate(content, velocityContext);
            kefuMessage = WxMpKefuMessage.TEXT().content(content).build();
        }

        return kefuMessage;
    }

    /**
     * 组织阿里云短信消息
     *
     * @param msgData 消息信息
     * @return SendSmsRequest
     */
    synchronized static SendSmsRequest makeAliyunMessage(String[] msgData) {
        SendSmsRequest request = new SendSmsRequest();
        //使用post提交
        request.setMethod(MethodType.POST);
        //必填:短信签名-可在短信控制台中找到
        request.setSignName(Init.configer.getAliyunSign());

        // 模板参数
        Map<String, String> paramMap = new HashMap<>();

        if (MainWindow.mainWindow.getTemplateMsgDataTable().getModel().getRowCount() == 0) {
            Init.initTemplateDataTable();
        }

        DefaultTableModel tableModel = (DefaultTableModel) MainWindow.mainWindow.getTemplateMsgDataTable().getModel();
        int rowCount = tableModel.getRowCount();

        VelocityContext velocityContext = new VelocityContext();
        for (int i = 0; i < msgData.length; i++) {
            velocityContext.put(TEMPLATE_VAR_PREFIX + i, msgData[i]);
        }
        for (int i = 0; i < rowCount; i++) {
            String key = (String) tableModel.getValueAt(i, 0);
            String value = ((String) tableModel.getValueAt(i, 1));
            value = TemplateUtil.evaluate(value, velocityContext);

            paramMap.put(key, value);
        }

        request.setTemplateParam(JSONUtil.parseFromMap(paramMap).toJSONString(0));

        // 短信模板ID，传入的模板必须是在阿里阿里云短信中的可用模板。示例：SMS_585014
        request.setTemplateCode(MainWindow.mainWindow.getMsgTemplateIdTextField().getText());

        return request;
    }

    /**
     * 组织阿里大于模板短信消息
     *
     * @param msgData 消息信息
     * @return AlibabaAliqinFcSmsNumSendRequest
     */
    synchronized static AlibabaAliqinFcSmsNumSendRequest makeAliTemplateMessage(String[] msgData) {
        AlibabaAliqinFcSmsNumSendRequest request = new AlibabaAliqinFcSmsNumSendRequest();
        // 用户可以根据该会员ID识别是哪位会员使用了你的应用
        request.setExtend("WePush");
        // 短信类型，传入值请填写normal
        request.setSmsType("normal");

        // 模板参数
        Map<String, String> paramMap = new HashMap<>();

        if (MainWindow.mainWindow.getTemplateMsgDataTable().getModel().getRowCount() == 0) {
            Init.initTemplateDataTable();
        }

        DefaultTableModel tableModel = (DefaultTableModel) MainWindow.mainWindow.getTemplateMsgDataTable().getModel();
        int rowCount = tableModel.getRowCount();
        VelocityContext velocityContext = new VelocityContext();
        for (int i = 0; i < msgData.length; i++) {
            velocityContext.put(TEMPLATE_VAR_PREFIX + i, msgData[i]);
        }
        for (int i = 0; i < rowCount; i++) {
            String key = (String) tableModel.getValueAt(i, 0);
            String value = ((String) tableModel.getValueAt(i, 1));
            value = TemplateUtil.evaluate(value, velocityContext);

            paramMap.put(key, value);
        }

        request.setSmsParamString(JSONUtil.parseFromMap(paramMap).toJSONString(0));

        // 短信签名，传入的短信签名必须是在阿里大鱼“管理中心-短信签名管理”中的可用签名。如“阿里大鱼”已在短信签名管理中通过审核，
        // 则可传入”阿里大鱼“（传参时去掉引号）作为短信签名。短信效果示例：【阿里大鱼】欢迎使用阿里大鱼服务。
        request.setSmsFreeSignName(Init.configer.getAliSign());
        // 短信模板ID，传入的模板必须是在阿里大鱼“管理中心-短信模板管理”中的可用模板。示例：SMS_585014
        request.setSmsTemplateCode(MainWindow.mainWindow.getMsgTemplateIdTextField().getText());

        return request;
    }

    /**
     * 组织腾讯云短信消息
     *
     * @param msgData 消息信息
     * @return String[]
     */
    synchronized static String[] makeTxyunMessage(String[] msgData) {
        if (MainWindow.mainWindow.getTemplateMsgDataTable().getModel().getRowCount() == 0) {
            Init.initTemplateDataTable();
        }

        DefaultTableModel tableModel = (DefaultTableModel) MainWindow.mainWindow.getTemplateMsgDataTable().getModel();
        int rowCount = tableModel.getRowCount();
        String[] params = new String[rowCount];

        VelocityContext velocityContext = new VelocityContext();
        for (int i = 0; i < msgData.length; i++) {
            velocityContext.put(TEMPLATE_VAR_PREFIX + i, msgData[i]);
        }
        for (int i = 0; i < rowCount; i++) {
            String value = ((String) tableModel.getValueAt(i, 1));
            value = TemplateUtil.evaluate(value, velocityContext);

            params[i] = value;
        }

        return params;
    }

    /**
     * 组织云片网短信消息
     *
     * @param msgData 消息信息
     * @return Map
     */
    synchronized static Map<String, String> makeYunpianMessage(String[] msgData) {
        Map<String, String> params = new HashMap<>(2);

        VelocityContext velocityContext = new VelocityContext();
        for (int i = 0; i < msgData.length; i++) {
            velocityContext.put(TEMPLATE_VAR_PREFIX + i, msgData[i]);
        }

        String text = MainWindow.mainWindow.getMsgYunpianMsgContentTextField().getText();
        text = TemplateUtil.evaluate(text, velocityContext);

        params.put(YunpianClient.TEXT, text);
        return params;
    }

    /**
     * 微信公众号配置
     *
     * @return WxMpConfigStorage
     */
    private static WxMpConfigStorage wxMpConfigStorage() {
        WxMpInMemoryConfigStorage configStorage = new WxMpInMemoryConfigStorage();
        if (StringUtils.isEmpty(Init.configer.getWechatAppId()) || StringUtils.isEmpty(Init.configer.getWechatAppSecret())) {
            JOptionPane.showMessageDialog(MainWindow.mainWindow.getSettingPanel(), "请先在设置中填写并保存公众号相关配置！", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        configStorage.setAppId(Init.configer.getWechatAppId());
        configStorage.setSecret(Init.configer.getWechatAppSecret());
        configStorage.setToken(Init.configer.getWechatToken());
        configStorage.setAesKey(Init.configer.getWechatAesKey());
        return configStorage;
    }

    /**
     * 微信小程序配置
     *
     * @return WxMaInMemoryConfig
     */
    private static WxMaInMemoryConfig wxMaConfigStorage() {
        WxMaInMemoryConfig configStorage = new WxMaInMemoryConfig();
        if (StringUtils.isEmpty(Init.configer.getMiniAppAppId()) || StringUtils.isEmpty(Init.configer.getMiniAppAppSecret())
                || StringUtils.isEmpty(Init.configer.getMiniAppToken()) || StringUtils.isEmpty(Init.configer.getMiniAppAesKey())) {
            JOptionPane.showMessageDialog(MainWindow.mainWindow.getSettingPanel(), "请先在设置中填写并保存小程序相关配置！", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        configStorage.setAppid(Init.configer.getMiniAppAppId());
        configStorage.setSecret(Init.configer.getMiniAppAppSecret());
        configStorage.setToken(Init.configer.getMiniAppToken());
        configStorage.setAesKey(Init.configer.getMiniAppAesKey());
        configStorage.setMsgDataFormat("JSON");
        return configStorage;
    }

    /**
     * 获取微信公众号工具服务
     *
     * @return WxMpService
     */
    public static WxMpService getWxMpService() {
        WxMpService wxMpService = new WxMpServiceImpl();
        WxMpConfigStorage wxMpConfigStorage = wxMpConfigStorage();
        if (wxMpConfigStorage != null) {
            wxMpService.setWxMpConfigStorage(wxMpConfigStorage);
        }
        return wxMpService;
    }

    /**
     * 获取微信小程序工具服务
     *
     * @return WxMaService
     */
    static WxMaService getWxMaService() {
        WxMaService wxMaService = new WxMaServiceImpl();
        wxMaService.setWxMaConfig(wxMaConfigStorage());
        return wxMaService;
    }

    /**
     * 推送停止或结束后保存数据
     */
    static void savePushData() throws IOException {
        File pushHisDir = new File(SystemUtil.configHome + "data" + File.separator + "push_his");
        if (!pushHisDir.exists()) {
            pushHisDir.mkdirs();
        }

        String msgName = MainWindow.mainWindow.getMsgNameField().getText();
        String nowTime = DateUtil.now().replaceAll(":", "_");

        String[] strArray;
        CSVWriter writer;

        // 保存已发送
        if (PushData.sendSuccessList.size() > 0) {
            File toSendFile = new File(SystemUtil.configHome + "data" +
                    File.separator + "push_his" + File.separator + msgName +
                    "-发送成功-" + nowTime + ".csv");
            if (!toSendFile.exists()) {
                toSendFile.createNewFile();
            }
            writer = new CSVWriter(new FileWriter(toSendFile));

            for (String[] str : PushData.sendSuccessList) {
                writer.writeNext(str);
            }
            writer.close();
        }

        // 保存未发送
        for (String[] str : PushData.sendSuccessList) {
            PushData.toSendList.remove(str);
        }
        for (String[] str : PushData.sendFailList) {
            PushData.toSendList.remove(str);
        }
        if (PushData.toSendList.size() > 0) {
            File unSendFile = new File(SystemUtil.configHome + "data" + File.separator +
                    "push_his" + File.separator + msgName + "-未发送-" + nowTime +
                    ".csv");
            if (!unSendFile.exists()) {
                unSendFile.createNewFile();
            }
            writer = new CSVWriter(new FileWriter(unSendFile));
            for (String[] str : PushData.toSendList) {
                writer.writeNext(str);
            }
            writer.close();
        }

        // 保存发送失败
        if (PushData.sendFailList.size() > 0) {
            File failSendFile = new File(SystemUtil.configHome + "data" + File.separator +
                    "push_his" + File.separator + msgName + "-发送失败-" + nowTime + ".csv");
            if (!failSendFile.exists()) {
                failSendFile.createNewFile();
            }
            writer = new CSVWriter(new FileWriter(failSendFile));
            for (String[] str : PushData.sendFailList) {
                writer.writeNext(str);
            }
            writer.close();
        }

        Init.initMemberTab();
        Init.initSettingTab();
    }

    /**
     * 输出到控制台和log
     *
     * @param log
     */
    public static void console(String log) {
        MainWindow.mainWindow.getPushConsoleTextArea().append(log + "\n");
        MainWindow.mainWindow.getPushConsoleTextArea().setCaretPosition(MainWindow.mainWindow.getPushConsoleTextArea().getText().length());
        logger.warn(log);
    }

}
