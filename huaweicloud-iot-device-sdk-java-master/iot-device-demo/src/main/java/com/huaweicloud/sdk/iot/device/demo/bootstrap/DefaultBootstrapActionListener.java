package com.huaweicloud.sdk.iot.device.demo.bootstrap;

import com.huaweicloud.sdk.iot.device.IoTDevice;
import com.huaweicloud.sdk.iot.device.bootstrap.BootstrapClient;
import com.huaweicloud.sdk.iot.device.client.requests.DeviceMessage;
import com.huaweicloud.sdk.iot.device.transport.ActionListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.net.URL;

public class DefaultBootstrapActionListener implements ActionListener {

    private static final Logger log = LogManager.getLogger(DefaultBootstrapActionListener.class);

    /**
     * BootstrapRequestTrigger是平台系统字段，如果收到此字段，设备侧需要发起引导。
     */
    private static final String BOOTSTRAP_MESSAGE = "BootstrapRequestTrigger";

    /**
     * IoT平台CA证书
     */
    private static final String IODTA_ROOT_CA = "ca.jks";

    private String deviceId;

    private String secret;

    private BootstrapClient bootstrapClient;

    private String bootstrapUri;

    public DefaultBootstrapActionListener(String deviceId, String secret, BootstrapClient bootstrapClient, String bootstrapUri) {
        this.deviceId = deviceId;
        this.secret = secret;
        this.bootstrapClient = bootstrapClient;
        this.bootstrapUri = bootstrapUri;
    }

    @Override
    public void onSuccess(Object context) {
        // 引导成功，获取到iot平台的地址
        String address = (String) context;
        log.info("bootstrap success:" + address);

        // 引导成功后关闭客户端
        bootstrapClient.close();

        // 加载iot平台的ca证书，进行服务端校验
        URL resource = DefaultBootstrapActionListener.class.getClassLoader().getResource(IODTA_ROOT_CA);
        File file = new File(resource.getPath());

        // 与iot平台建立连接
        IoTDevice device = new IoTDevice("ssl://" + address, deviceId, secret, file);
        if (device.init() != 0) {
            return;
        }

        // 接收下发消息
        device.getClient().setDeviceMessageListener(deviceMessage -> {
            // 收到重引导消息，发起引导
            if (BOOTSTRAP_MESSAGE.equals(deviceMessage.getContent())) {
                // 关闭客户端
                device.getClient().close();

                // 创建引导客户端，发起引导
                BootstrapClient bootstrapClient = new BootstrapClient(bootstrapUri, deviceId, secret);
                DefaultBootstrapActionListener defaultBootstrapActionListener = new DefaultBootstrapActionListener(deviceId,
                        secret, bootstrapClient, bootstrapUri);
                bootstrapClient.bootstrap(defaultBootstrapActionListener);
            }
        });

        // 上报消息
        device.getClient().reportDeviceMessage(new DeviceMessage("hello"), null);
    }

    @Override
    public void onFailure(Object context, Throwable var2) {
        // 引导失败
        log.error("bootstrap failed: {}", var2.getMessage());
    }
}
