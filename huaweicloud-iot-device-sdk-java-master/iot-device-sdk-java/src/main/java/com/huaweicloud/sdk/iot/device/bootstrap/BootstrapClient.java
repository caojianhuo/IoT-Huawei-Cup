package com.huaweicloud.sdk.iot.device.bootstrap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huaweicloud.sdk.iot.device.client.ClientConf;
import com.huaweicloud.sdk.iot.device.transport.ActionListener;
import com.huaweicloud.sdk.iot.device.transport.Connection;
import com.huaweicloud.sdk.iot.device.transport.RawMessage;
import com.huaweicloud.sdk.iot.device.transport.RawMessageListener;
import com.huaweicloud.sdk.iot.device.transport.mqtt.MqttConnection;
import com.huaweicloud.sdk.iot.device.utils.JsonUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.net.URL;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 引导客户端，用于设备引导来获取服务端地址
 */
public class BootstrapClient implements RawMessageListener {

    private String deviceId;

    private Connection connection;

    private ActionListener listener;

    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    private static final Logger log = LogManager.getLogger(BootstrapClient.class);

    /**
     * 设备发放的设备侧CA证书，注意与IoTDA的设备侧区分开
     */
    private static final String BOOTSTRAP_CA_RES_PATH = "bsca.jks";

    /**
     * 设备发放定义的TOPIC
     */
    private static final String BOOTSTRAP_PUBLISH_TOPIC = "$oc/devices/%s/sys/bootstrap/up";
    private static final String BOOTSTRAP_SUBSCRIBE_TOPIC = "$oc/devices/%s/sys/bootstrap/down";

    /**
     * 构造函数，使用密码创建
     *
     * @param bootstrapUri bootstrap server地址，比如ssl://iot-bs.cn-north-4.myhuaweicloud.com:8883
     * @param deviceId     设备id
     * @param deviceSecret 设备密码
     */
    public BootstrapClient(String bootstrapUri, String deviceId, String deviceSecret) {

        ClientConf clientConf = new ClientConf();
        clientConf.setServerUri(bootstrapUri);
        clientConf.setFile(getBootstrapPlatformCaFile());
        clientConf.setDeviceId(deviceId);
        clientConf.setSecret(deviceSecret);
        this.deviceId = deviceId;
        this.connection = new MqttConnection(clientConf, this);
        log.info("create BootstrapClient: " + clientConf.getDeviceId());

    }

    /**
     * 构造函数，使用证书创建
     *
     * @param bootstrapUri bootstrap server地址，比如ssl://iot-bs.cn-north-4.myhuaweicloud.com:8883
     * @param deviceId     设备id
     * @param keyStore     证书容器
     * @param keyPassword  证书密码
     */
    public BootstrapClient(String bootstrapUri, String deviceId, KeyStore keyStore, String keyPassword) {

        ClientConf clientConf = new ClientConf();
        clientConf.setServerUri(bootstrapUri);
        clientConf.setFile(getBootstrapPlatformCaFile());
        clientConf.setDeviceId(deviceId);
        clientConf.setKeyPassword(keyPassword);
        clientConf.setKeyStore(keyStore);
        this.deviceId = deviceId;
        this.connection = new MqttConnection(clientConf, this);
        log.info("create BootstrapClient: " + clientConf.getDeviceId());
    }

    /**
     * 构造函数，自注册场景下证书创建
     *
     * @param bootstrapUri bootstrap server地址，比如ssl://iot-bs.cn-north-4.myhuaweicloud.com:8883
     * @param deviceId     设备id
     * @param keyStore     证书容器
     * @param keyPassword  证书密码
     * @param scopeId      scopeId, 自注册场景可从物联网平台获取
     */
    public BootstrapClient(String bootstrapUri, String deviceId, KeyStore keyStore, String keyPassword,
                           String scopeId) {
        ClientConf clientConf = new ClientConf();
        clientConf.setServerUri(bootstrapUri);
        clientConf.setFile(getBootstrapPlatformCaFile());
        clientConf.setDeviceId(deviceId);
        clientConf.setKeyStore(keyStore);
        clientConf.setKeyPassword(keyPassword);
        clientConf.setScopeId(scopeId);
        this.deviceId = deviceId;
        this.connection = new MqttConnection(clientConf, this);
        log.info("create BootstrapClient: " + clientConf.getDeviceId());
    }

    public File getBootstrapPlatformCaFile() {
        //加载iot平台（设备发放）的ca证书，进行服务端校验
        URL resource = BootstrapClient.class.getClassLoader().getResource(BOOTSTRAP_CA_RES_PATH);
        return new File(resource.getPath());
    }

    @Override
    public void onMessageReceived(RawMessage message) {
        String bsTopic = String.format(BOOTSTRAP_SUBSCRIBE_TOPIC, this.deviceId);
        if (message.getTopic().equals(bsTopic)) {
            ObjectNode node = JsonUtil.convertJsonStringToObject(message.toString(), ObjectNode.class);
            String address = node.get("address").asText();
            log.info("bootstrap ok address:" + address);

            Future<String> success = executorService.submit(() -> listener.onSuccess(address), "success");
            String result = "";
            try {
                result = success.get();
            } catch (Exception e) {
                log.error("get submit result failed " + e.getMessage());
            }

            if (result.equals("success")) {
                log.debug("submit task succeeded");
            }

        }
    }

    /**
     * 发起设备引导
     *
     * @param listener 监听器用来接收引导结果
     * @throws IllegalArgumentException 参数非法异常
     */
    public void bootstrap(ActionListener listener) throws IllegalArgumentException {

        this.listener = listener;

        if (connection.connect() != 0) {
            log.error("connect failed");
            listener.onFailure(null, new Exception("connect failed"));
            return;
        }

        String bsTopic = String.format(BOOTSTRAP_SUBSCRIBE_TOPIC, this.deviceId);

        connection.subscribeTopic(bsTopic, null, 0);

        String topic = String.format(BOOTSTRAP_PUBLISH_TOPIC, this.deviceId);
        RawMessage rawMessage = new RawMessage(topic, "");

        connection.publishMessage(rawMessage, null);

    }

    /**
     * 关闭客户端
     */
    public void close() {
        connection.close();
        executorService.shutdown();
    }
}
